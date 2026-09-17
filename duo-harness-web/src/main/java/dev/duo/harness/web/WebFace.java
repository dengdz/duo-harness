package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolsService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Web 双面呈现位（M8）：JDK 内置 HttpServer 承载的本地服务——静态单页（双区布局，
 * 样式与脚本经 {@code /web/} 白名单资源服务）、`/api/status`（插件快照 + 工具清单 JSON）、
 * `/api/events`（SSE 会话事件流）、`/api/message`（对话入口，虚拟线程异步执行 agent.send）、
 * `/api/session/new`（开新会话）、`/api/answer`（HITL 回答完成，接 M6 交互 seam 的 Web answerer）。
 *
 * <p>只绑定 127.0.0.1（ADR-0007 v3 安全基线，鉴权 M9+）；执行器用虚拟线程
 * （每任务一线程，SSE 长连接不占平台线程，ADR-0002 同源）。SSE 连接带 15s
 * 心跳帧保活（写失败即摘除死连接，兼防代理静默断连）；事件经会话监听器广播；
 * 全部客户端断开且宽限期内无新连接入列时悬空交互 fail-closed（经 {@link WebAnswerer}，
 * ADR-0008 / ADR-0010 延伸——判定语义是"是否仍有人能看见该审批"，刷新断旧立新不误杀）。</p>
 */
public final class WebFace {

    private static final Logger log = LoggerFactory.getLogger(WebFace.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;
    /** POST 请求体大小上限（1MB）：防误粘贴/恶意超大 body 占内存，正常对话文本远低于此。 */
    private static final int MAX_BODY_BYTES = 1_000_000;
    /** 会话 id 白名单（Session.newId 的生成形态：日期时间 + 4 位十六进制后缀）。 */
    private static final java.util.regex.Pattern SESSION_ID =
            java.util.regex.Pattern.compile("\\d{8}-\\d{6}-[0-9a-f]{4}");
    /**
     * fail-closed 去抖宽限（毫秒）：摘除死连接后列表暂空不立即拒——浏览器刷新的
     * "断旧立新"窗口里新连接可能尚未入列，立即拒会误杀仍有人能答的审批。
     */
    static final long FAIL_CLOSED_GRACE_MS = 2_000;
    /** SSE 游标请求头（浏览器重连自动携带，值为最后收到的 id）。 */
    private static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";
    /** 首屏尾部窗口的消息数（ADR-0013：常量起步不进 yml，页长配置化为已知限制）。 */
    static final int TAIL_WINDOW_MESSAGES = 50;
    private final HttpServer server;
    private final Context ctx;
    private final ToolsService tools;
    /** SSE 客户端连接（多客户端广播，心跳写失败即摘除）。 */
    private final CopyOnWriteArrayList<SseClient> sseOutputs = new CopyOnWriteArrayList<>();
    /** HITL Web answerer（审批/提问的 Web 呈现位）。 */
    private final WebAnswerer webAnswerer;
    /** 上下文治理（状态面占用查询的同源数据源；null = 无治理装配，状态面省略占用）。 */
    private volatile dev.duo.harness.agent.governance.ContextGovernance governance;
    /** 会话目录（侧栏列表与切换用）。 */
    private final Path sessionsDir;
    /** 可换会话（/new 等价）：换绑时 SSE 监听器随之迁移。 */
    private volatile Session session;
    /** 对话执行者（/new 重建；volatile 保证跨线程可见）。 */
    private volatile ChatAgent agent;
    /** 单飞标志：一次只跑一轮 send（CLI 单入口同约定）。 */
    private final AtomicBoolean busy = new AtomicBoolean(false);
    /** 心跳调度器（保活 + 死连接摘除）。 */
    private final java.util.concurrent.ScheduledExecutorService heartbeat =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "web-sse-heartbeat");
                t.setDaemon(true);
                return t;
            });
    /** 新会话供给者（/new 每次给全新会话）。 */
    private volatile java.util.function.Supplier<Session> newSessionSupplier =
            () -> { throw new IllegalStateException("新会话供给者未装配"); };
    /**
     * 会话变更回调（/new 与 /switch 共用）：装配层以入参会话重建 agent——
     * ToolCallingAgent 持有 final 会话引用，不重建即分脑（消息落旧会话、
     * 页面显示新会话，BUG-20260914-02）。
     */
    private volatile Consumer<Session> sessionChangedCallback = changed -> { };

    /** fail-closed 去抖复查任务（同一时刻至多一个；窗口内新摘除会重置窗口）。 */
    private final AtomicReference<java.util.concurrent.ScheduledFuture<?>> failClosedCheck =
            new AtomicReference<>();

    private WebFace(HttpServer server, Context ctx, ToolsService tools, Session session,
                    WebAnswerer webAnswerer, Path sessionsDir) {
        this.server = server;
        this.ctx = ctx;
        this.tools = tools;
        this.session = session;
        this.webAnswerer = webAnswerer;
        this.sessionsDir = sessionsDir;
    }

    /**
     * 启动并绑定 127.0.0.1:port（port 0 = 系统随机分配，测试用）。
     * governance 可为 null（无治理装配时状态面省略上下文占用字段）。
     *
     * <p><b>会话所有权</b>：WebFace 接管传入会话的生命周期——换绑（/new、/switch）时
     * 关闭旧会话释放其独占锁，{@link #stop()} 关闭当前会话。调用方无须（也不应）再关闭。</p>
     *
     * @throws IOException 端口绑定失败
     */
    public static WebFace start(int port, Context ctx, ToolsService tools, Session session,
                                ChatAgent agent, dev.duo.harness.agent.governance.ContextGovernance governance,
                                WebAnswerer webAnswerer, Path sessionsDir)
            throws IOException {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(agent, "agent");
        // webAnswerer 可为 null（骨架用例不测 HITL）；non-null 时必有 sessionsDir
        if (webAnswerer != null) {
            Objects.requireNonNull(sessionsDir, "sessionsDir");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        WebFace face = new WebFace(server, ctx, tools, session, webAnswerer, sessionsDir);
        face.governance = governance;
        face.bindSession(session);
        face.agent = agent;
        face.registerEndpoints();
        face.heartbeat.scheduleAtFixedRate(face::pingAll,
                HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        server.start();
        return face;
    }

    /** 换绑串行化锁：并发切换（连点侧栏/新话题）下保证"关闭上一个"链条线性、会话锁不泄漏。 */
    private final Object bindLock = new Object();

    /** 绑定会话的事件监听（SSE 推送源）；换会话时先解绑旧的，并释放旧会话的独占锁。 */
    private void bindSession(Session target) {
        synchronized (bindLock) {
            Session previous = session;
            session = target;
            if (sseSubscription != null) {
                try {
                    sseSubscription.dispose();
                } catch (Exception e) {
                    // 旧监听器注销失败无碍：新订阅已就位
                }
            }
            sseSubscription = target.addListener(this::pushSessionEvent);
            if (previous != null && previous != target) {
                // 换绑即本进程不再使用旧会话：释放独占锁（否则旧会话被本进程白占，他处打不开）。
                // 必须串行：并发换绑各关各的快照会跳过中间会话，其独占锁永久泄漏
                previous.close();
            }
        }
    }

    private Disposable sseSubscription;

    /** 换绑会话并通知装配层重建 agent（/new 语义；回调拿到的是已换绑的同一会话）。 */
    private void newSession() {
        Session fresh = newSessionSupplier.get();
        bindSession(fresh);
        sessionChangedCallback.accept(fresh);
    }

    /** 注册会话变更回调（装配层接线；/new 与 /switch 换绑后都回调重建 agent）。 */
    void onSessionChanged(java.util.function.Consumer<Session> onChanged) {
        this.sessionChangedCallback = java.util.Objects.requireNonNull(onChanged, "onChanged");
    }

    /** 注册 /new 的供给者（装配层接线；供 WebPlugin 调用，包级可见）。 */
    void onNewSession(java.util.function.Supplier<Session> supplier) {
        this.newSessionSupplier = java.util.Objects.requireNonNull(supplier, "supplier");
    }

    /** 测试与装配层用：替换对话执行者（/new 重建后调用）。 */
    void setAgent(ChatAgent agent) {
        this.agent = agent;
    }

    /** 当前会话（装配层重建 agent 时取用）。 */
    Session currentSession() {
        return session;
    }

    /** 会话事件广播：帧带日志序号 id——浏览器以最后收到的 id 作重连游标（ADR-0010）。 */
    private void pushSessionEvent(int index, SessionEvent event) {
        // 序号由会话在写入处随回调给出（不从日志末尾反推——并发追加下反推会错位）
        String frame = toJson(event);
        broadcast(client -> client.send(dataFrameWithId(index, frame)));
    }

    /** 非会话帧广播（run/error 等直推帧）：帧不带序号，契约见 {@link #dataFrame}。 */
    private void pushTransientFrame(String payload) {
        broadcast(client -> client.send(dataFrame(payload)));
    }

    /** 帧写动作（写失败 IOException 即摘除该连接）。 */
    @FunctionalInterface
    private interface FrameSink {

        void write(SseClient client) throws IOException;
    }

    /** 广播到全部连接：逐连接执行帧写，写失败即摘除死连接。 */
    private void broadcast(FrameSink sink) {
        for (SseClient client : sseOutputs.toArray(SseClient[]::new)) {
            try {
                sink.write(client);
            } catch (IOException e) {
                removeClient(client);
            }
        }
    }

    /** 心跳：向全部 SSE 客户端写注释帧，写失败即摘除死连接。 */
    private void pingAll() {
        broadcast(client -> client.send(": ping\n\n"));
    }

    /** SSE 客户端连接：输出流 + 帧写串行化。回放（连接线程）与实时广播（写线程）
     *  会并发写同一连接，不加锁则帧字节交错、前端解析失败。 */
    static final class SseClient {

        private final OutputStream out;

        SseClient(OutputStream out) {
            this.out = out;
        }

        /** 单帧原子写（含 flush）。 */
        synchronized void send(String frame) throws IOException {
            out.write(frame.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        synchronized void close() {
            try {
                out.close();
            } catch (IOException ignored) {
                // 连接已死
            }
        }
    }

    /**
     * 摘除死连接；全部客户端离场时悬空交互按"无人能答"拒绝（ADR-0008 语义延伸）。
     * 拒绝经 {@link #scheduleFailClosedCheck()} 去抖：立即判空会误杀刷新场景
     * （断旧立新窗口里新连接尚未入列）。包级可见供测试确定性驱动摘除时点——
     * 传入的连接即使不在列表中也生效：判定只看"摘除后列表是否为空"。
     */
    void removeClient(SseClient client) {
        sseOutputs.remove(client);
        if (webAnswerer != null && sseOutputs.isEmpty()) {
            scheduleFailClosedCheck();
        }
    }

    /** 调度去抖复查：宽限后仍无任何连接才 fail-closed；窗口内新摘除重置窗口。 */
    private void scheduleFailClosedCheck() {
        java.util.concurrent.ScheduledFuture<?> prior = failClosedCheck.getAndSet(
                heartbeat.schedule(this::failClosedIfNoClient, FAIL_CLOSED_GRACE_MS, TimeUnit.MILLISECONDS));
        if (prior != null) {
            prior.cancel(false);
        }
    }

    /** 宽限期到：仍无任何客户端在场才判定"无人能答"。 */
    private void failClosedIfNoClient() {
        if (webAnswerer != null && sseOutputs.isEmpty()) {
            webAnswerer.failClosedAll();
        }
    }

    private void registerEndpoints() {
        // 静态单页
        server.createContext("/", exchange -> {
            byte[] page = readClasspage();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(page);
            }
        });
        // 静态资源（样式/脚本/vendor 库同路）：/web/ 前缀 + 单段已知后缀文件名白名单——
        // 多段路径、.. 与未知后缀一律 404，资源缺失也 404（不落回单页，坏引用不伪装成功）
        server.createContext("/web/", exchange -> {
            String name = exchange.getRequestURI().getPath().substring("/web/".length());
            String type = name.isEmpty() || name.contains("/") || name.contains("..")
                    ? null : STATIC_TYPES.get(suffixOf(name));
            byte[] body = type == null ? null : readClassResource("/web/" + name);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        // 状态面 JSON
        server.createContext("/api/status", exchange -> {
            byte[] body = statusJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        // 对话入口：立即 202，虚拟线程异步执行 agent.send；
        // user/message、tool/call、tool/result、assistant/message 由 agent 侧追加（经会话监听器广播），
        // assistant/chunk 由本端 AgentListener 追加（Web 面只补这一种会话事件）。
        server.createContext("/api/message", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] raw = readBodyLimited(exchange);
            if (raw == null) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            String body = new String(raw, StandardCharsets.UTF_8);
            String text;
            try {
                JsonNode node = JSON.readTree(body);
                text = node.path("text").asText("");
            } catch (Exception e) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            if (text.isBlank()) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            ChatAgent current = agent;
            if (current == null) {
                byte[] msg = "对话面未就绪（agent 未装配）".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(503, msg.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(msg); }
                return;
            }
            if (!busy.compareAndSet(false, true)) {
                byte[] msg = "已有对话在执行中（单入口串行）".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(409, msg.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(msg); }
                return;
            }
            exchange.sendResponseHeaders(202, -1);
            Thread.ofVirtual().start(() -> {
                try {
                    current.send(text, new dev.duo.harness.agent.AgentListener() {
                        @Override
                        public void onChunk(String chunk) {
                            session.append(SessionEvent.assistantChunk(chunk));
                        }
                    });
                } catch (Exception e) {
                    // 错误呈现：非会话事件直推帧（页面渲染 [错误] 卡），不污染会话历史；
                    // 帧内只给通用文案——异常细节服务端日志留痕，不外推（M10-02 脱敏口径）
                    log.warn("消息处理失败", e);
                    pushTransientFrame(toJson(SessionEvent.errorEvent("消息处理失败，详情见服务端日志")));
                } finally {
                    busy.set(false);
                }
            });
        });
        // 开新会话：换绑事件流 + 通知装配层重建 agent（供给者未装配/创建失败 → 500，不断连接）
        server.createContext("/api/session/new", exchange -> {
            byte[] discarded = readBodyLimited(exchange); // 请求体必须清空（keep-alive 连接复用正确性）
            if (discarded == null) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                newSession();
            } catch (Exception e) {
                // 异常细节仅服务端日志留痕——错误响应不回显内部消息（M10-02 脱敏）
                log.warn("新会话创建失败", e);
                byte[] msg = "新会话创建失败".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(500, msg.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(msg); }
                return;
            }
            byte[] body = ("{\"id\":\"" + session.id() + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        // 会话列表（侧栏）：修改时间倒序
        server.createContext("/api/sessions", exchange -> {
            byte[] body = sessionsJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        // 切换会话：{id} → 加载该会话并换绑（SSE 推送新会话存量回放）；
        // 会话变更回调重建 agent——不重建即分脑（agent 写旧会话、页面看新会话）。
        // id 按生成形态白名单校验：路径分隔符/穿越串一律 400，不进路径解析
        server.createContext("/api/session/switch", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] raw = readBodyLimited(exchange);
            if (raw == null) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            try {
                String id = JSON.readTree(new String(raw, StandardCharsets.UTF_8)).path("id").asText("");
                if (id.isBlank() || !SESSION_ID.matcher(id).matches()) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                if (id.equals(session.id())) {
                    // 切到当前会话：幂等成功——重新 load 自己必撞独占锁（OverlappingFileLockException），
                    // 而语义上本就无需动作（侧栏点当前项、重复提交切换请求都不该失败）
                    byte[] ok = "{\"switched\":true}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200, ok.length);
                    try (OutputStream out = exchange.getResponseBody()) { out.write(ok); }
                    return;
                }
                Session loaded = Session.load(sessionsDir.resolve(id + ".jsonl"));
                bindSession(loaded);
                sessionChangedCallback.accept(loaded);
                byte[] ok = "{\"switched\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, ok.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(ok); }
            } catch (dev.duo.harness.session.SessionLockedException e) {
                // 会话被占（本进程另一入口或其他进程在用）：明确点名冲突，不混入通用失败文案
                log.info("会话切换被拒（占用冲突）: {}", e.getMessage());
                byte[] msg = e.brief().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(409, msg.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(msg); }
            } catch (Exception e) {
                // 异常细节（含文件系统路径）仅服务端日志留痕，不回显给响应体（M10-02 脱敏）
                log.warn("会话切换失败", e);
                byte[] msg = "切换失败：会话不存在或不可读".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(404, msg.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(msg); }
            }
        });
        // HITL 回答端点：{approved: bool} 或 {values: ["..."]} → 完成 WebAnswerer 悬空请求
        server.createContext("/api/answer", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (webAnswerer == null) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            byte[] raw = readBodyLimited(exchange);
            if (raw == null) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            String body = new String(raw, StandardCharsets.UTF_8);
            boolean completed;
            try {
                JsonNode node = JSON.readTree(body);
                // 审批：{approved: true/false}；提问/计划：{values: ["..."]}；混合兼容
                if (node.has("values") && node.get("values").isArray()) {
                    List<String> values = new java.util.ArrayList<>();
                    node.get("values").forEach(n -> values.add(n.asText()));
                    completed = webAnswerer.complete(!values.isEmpty() && !"拒绝".equals(values.get(0)), values);
                } else {
                    boolean approved = node.path("approved").asBoolean(false);
                    completed = webAnswerer.complete(approved, List.of());
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            byte[] resp = ("{\"completed\":" + completed + "}").getBytes(StandardCharsets.UTF_8);
            log.debug("/api/answer completed={}", completed);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(resp); }
        });
        // 历史分页（ADR-0013）：before（事件序号）之前的尾页事件——响应携 startEvent（窗口首事件
        // 下标，前端更新加载锚点）、events、hasMore、earlierCount；服务端每次全量投影定消息边界
        server.createContext("/api/session/page", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            int before;
            try {
                before = Integer.parseInt(queryParam(exchange, "before"));
            } catch (NumberFormatException e) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            Session bound = session;
            List<SessionEvent> events = bound.events();
            if (before < 0 || before > events.size()) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            Session.TailWindow window = bound.windowBefore(before, TAIL_WINDOW_MESSAGES); // 首屏/每页同值（ADR-0013）
            var root = JSON.createObjectNode()
                    .put("startEvent", window.startEvent())
                    .put("hasMore", window.earlierMessages() > 0)
                    .put("earlierCount", window.earlierMessages());
            var arr = root.putArray("events");
            for (int i = window.startEvent(); i < before; i++) {
                arr.add(JSON.valueToTree(events.get(i)));
            }
            byte[] body = root.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        // 子任务回放（M15 工单 05，ADR-0015 决策 3）：子会话事件只读回放——静态逐行读
        // **不持锁**（活跃子会话读到部分文件即所见，不与子代理写者争锁）；id 白名单
        // 防路径穿越（与侧栏切换同一 SESSION_ID 形态）；坏行跳过（回放是锦上添花，
        // 不因单行损坏失败）
        server.createContext("/api/subagent/events", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String id = queryParam(exchange, "id");
            if (id == null || !SESSION_ID.matcher(id).matches()) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            Path jsonl = sessionsDir.resolve(dev.duo.harness.agent.subagent.SubagentManager.SUBDIRECTORY)
                    .resolve(id + ".jsonl");
            var root = JSON.createObjectNode();
            var arr = root.putArray("events");
            root.put("found", Files.isRegularFile(jsonl));
            if (Files.isRegularFile(jsonl)) {
                try {
                    for (String line : Files.readAllLines(jsonl)) {
                        if (line.isBlank()) {
                            continue;
                        }
                        try {
                            arr.add(JSON.readTree(line));
                        } catch (Exception ignored) {
                            // 单行损坏跳过（探测语义宽松）
                        }
                    }
                } catch (IOException e) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
            }
            byte[] body = root.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        // SSE 会话事件流：连接帧 + 回放（尾部快照/增量）+ 实时广播（断开摘除输出流）。
        // 首连（无 Last-Event-ID）发尾部窗口快照（ADR-0013）；断线重连带游标只补其后事件（ADR-0010）
        server.createContext("/api/events", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            SseClient client = new SseClient(exchange.getResponseBody());
            sseOutputs.add(client);
            try {
                // 连接帧是 SSE 注释（冒号行），不是 data 帧——前端 JSON.parse 不消费它
                client.send(": connected\n\n");
                // 回放窗口：replay/start 告知模式与窗口头（前端据此整窗替换或保留存量）→ 事件帧（带
                // 日志序号 id）→ replay/done 边界帧（前端回放结束钩子：EmptyHero 判定与侧栏刷新）
                String cursor = exchange.getRequestHeaders().getFirst(LAST_EVENT_ID_HEADER);
                Session bound = session; // 单次取用：换绑并发下事件快照与窗口映射必须同源
                List<SessionEvent> events = bound.events(); // 共享不可变快照（ADR-0014）：一次取用遍历全程稳定
                ReplayWindow window = resolveReplayWindow(cursor, events, bound);
                // 连接观测：回放模式与游标——诊断重连行为（断线重连应见 incremental）
                log.debug("SSE 连接：模式={}，游标={}，事件数={}", window.mode(), cursor, events.size());
                var header = JSON.createObjectNode().put("type", "replay/start").put("mode", window.mode());
                if (window.tailSnapshot()) {
                    header.put("hasMore", window.hasMore()).put("earlierCount", window.earlierCount());
                }
                client.send(dataFrame(header.toString()));
                for (int i = window.from(); i < events.size(); i++) {
                    client.send(dataFrameWithId(i, toJson(events.get(i))));
                }
                client.send(dataFrame("{\"type\":\"replay/done\"}"));
            } catch (Exception e) {
                // 回放中断（含运行时异常）即摘除断连——客户端经 EventSource 重连重新回放
                removeClient(client);
                exchange.close();
            }
        });
    }

    /** 回放窗口：起点下标 + 模式；尾部快照模式头帧额外携带 hasMore 与更早计数。 */
    private record ReplayWindow(int from, String mode, boolean tailSnapshot,
                                boolean hasMore, int earlierCount) { }

    /**
     * 解析重连游标决定回放窗口：游标合法且落在日志范围内 → 只补其后事件（增量，ADR-0010）；
     * 无游标或游标非法/越界 → 尾部窗口快照（ADR-0013）——投影取尾部 {@link #TAIL_WINDOW_MESSAGES}
     * 条消息的事件区间，头帧带 hasMore（是否还有更早消息）与更早计数。日志 append-only、
     * 治理为纯读侧（不改编号），越界游标只见于跨会话误用——按首连同样兜底。
     */
    private static ReplayWindow resolveReplayWindow(String cursor, List<SessionEvent> events, Session bound) {
        if (cursor != null && !cursor.isBlank()) {
            try {
                int parsed = Integer.parseInt(cursor.strip());
                if (parsed >= 0 && parsed < events.size()) {
                    return new ReplayWindow(parsed + 1, "incremental", false, false, 0);
                }
            } catch (NumberFormatException ignored) {
                // 非法游标按无游标处理（尾部快照兜底）
            }
        }
        Session.TailWindow tail = bound.tailWindow(TAIL_WINDOW_MESSAGES);
        return new ReplayWindow(tail.startEvent(), "tail-snapshot", true,
                tail.earlierMessages() > 0, tail.earlierMessages());
    }

    /**
     * 非会话帧（replay/start、replay/done、run/error、心跳注释）：不带序号——这些帧
     * 不落会话日志，无下标可锚；给它们安上别的序号会污染浏览器游标（客户端会误认为该
     * 序号的日志事件已收到，重连时跳过它）。
     */
    private static String dataFrame(String payload) {
        return "data: " + payload.replace("\n", "\ndata: ") + "\n\n";
    }

    /** 会话事件帧：带日志序号 id——浏览器重连自动以 Last-Event-ID 回传作游标（ADR-0010）。 */
    private static String dataFrameWithId(int id, String payload) {
        return "id: " + id + "\n" + dataFrame(payload);
    }

    private String toJson(SessionEvent event) {
        try {
            return JSON.writeValueAsString(event);
        } catch (IOException e) {
            throw new IllegalStateException("事件 JSON 序列化失败", e);
        }
    }

    /** 侧栏 JSON：会话列表（修改时间倒序，current 标记当前会话，occupied 占用探测、title 标题——工单 M13-05/06）。 */
    private String sessionsJson() {
        var root = JSON.createObjectNode();
        var arr = root.putArray("sessions");
        String currentId = session.id();
        for (Session.SessionSummary summary : Session.list(sessionsDir)) {
            Path jsonl = sessionsDir.resolve(summary.id() + ".jsonl");
            var node = arr.addObject()
                    .put("id", summary.id())
                    .put("lastModifiedMs", summary.lastModifiedMs())
                    .put("occupied", Session.isOccupied(jsonl))
                    .put("title", Session.titleOf(jsonl));
            node.put("current", summary.id().equals(currentId));
        }
        return root.toString();
    }

    /** 状态面 JSON：插件快照 + 工具清单 + 上下文占用（与治理计量同源，无治理时省略）。 */
    private String statusJson() {
        try {
            var root = JSON.createObjectNode();
            var plugins = root.putArray("plugins");
            for (var snapshot : ctx.snapshots()) {
                plugins.addObject().put("name", snapshot.name()).put("state", snapshot.state().name());
            }
            var toolsNode = root.putArray("tools");
            for (var definition : tools.list()) {
                toolsNode.addObject().put("name", definition.name()).put("description", definition.description());
            }
            dev.duo.harness.agent.governance.ContextGovernance current = governance;
            if (current != null) {
                var occupancy = current.occupancy(session);
                root.putObject("context")
                        .put("tokens", occupancy.tokens())
                        .put("thresholdTokens", occupancy.thresholdTokens())
                        .put("windowTokens", occupancy.windowTokens())
                        .put("fromProvider", occupancy.fromProvider());
            }
            return root.toString();
        } catch (Exception e) {
            throw new IllegalStateException("状态面 JSON 构建失败", e);
        }
    }

    private byte[] readClasspage() {
        byte[] page = readClassResource("/web/index.html");
        return page != null ? page
                : "<html><body><p>web/index.html 资源缺失</p></body></html>".getBytes(StandardCharsets.UTF_8);
    }

    /** 静态资源后缀 → Content-Type（白名单外不服务）。 */
    private static final java.util.Map<String, String> STATIC_TYPES = java.util.Map.of(
            "html", "text/html",
            "css", "text/css",
            "js", "application/javascript");

    private static String suffixOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** 读取请求体并施加大小上限：超限返回 null（调用方回 413），最多读上限+1 字节防内存放大。 */
    private static byte[] readBodyLimited(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        return body.length > MAX_BODY_BYTES ? null : body;
    }

    /** 取查询参数原值（缺参返回空串，由调用方解析并决定成败——不做 URL 解码，参数集仅限简单值）。 */
    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return "";
    }

    /** 读 classpath 资源；缺失或读失败返回 null（404 语义由调用方定）。 */
    private static byte[] readClassResource(String path) {
        try (var in = WebFace.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** 实际绑定端口（构造传 0 时为系统分配值）。 */
    public int port() {
        return server.getAddress().getPort();
    }

    /** 当前活跃的 SSE 连接数（观测用）。 */
    public int sseConnections() {
        return sseOutputs.size();
    }

    /** 停止服务与心跳（插件 dispose 调用），并关闭当前会话（会话所有权见 {@link #start}）。 */
    public void stop() {
        heartbeat.shutdownNow();
        for (SseClient client : List.copyOf(sseOutputs)) {
            client.close();
        }
        sseOutputs.clear();
        server.stop(0);
        session.close();
    }
}
