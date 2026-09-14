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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * 全部客户端断开时悬空交互 fail-closed（经 {@link WebAnswerer}，ADR-0008 延伸）。</p>
 */
public final class WebFace {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;
    /** POST 请求体大小上限（1MB）：防误粘贴/恶意超大 body 占内存，正常对话文本远低于此。 */
    private static final int MAX_BODY_BYTES = 1_000_000;
    /** 会话 id 白名单（Session.newId 的生成形态：日期时间 + 4 位十六进制后缀）。 */
    private static final java.util.regex.Pattern SESSION_ID =
            java.util.regex.Pattern.compile("\\d{8}-\\d{6}-[0-9a-f]{4}");

    private final HttpServer server;
    private final Context ctx;
    private final ToolsService tools;
    /** SSE 客户端输出流（多客户端广播，心跳写失败即摘除）。 */
    private final CopyOnWriteArrayList<OutputStream> sseOutputs = new CopyOnWriteArrayList<>();
    /** HITL Web answerer（审批/提问的 Web 呈现位）。 */
    private final WebAnswerer webAnswerer;
    /** 上下文治理（状态面占用查询的同源数据源；null = 无治理装配，状态面省略占用）。 */
    private volatile dev.duo.harness.agent.ContextGovernance governance;
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
     * @throws IOException 端口绑定失败
     */
    public static WebFace start(int port, Context ctx, ToolsService tools, Session session,
                                ChatAgent agent, dev.duo.harness.agent.ContextGovernance governance,
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

    /** 绑定会话的事件监听（SSE 推送源）；换会话时先解绑旧的。 */
    private void bindSession(Session target) {
        session = target;
        if (sseSubscription != null) {
            try {
                sseSubscription.dispose();
            } catch (Exception e) {
                // 旧监听器注销失败无碍：新订阅已就位
            }
        }
        sseSubscription = session.addListener(this::pushEvent);
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

    /** SSE 帧推送：单个会话事件广播到全部客户端（写失败的连接摘除）。 */
    private void pushEvent(SessionEvent event) {
        String frame = toJson(event);
        for (OutputStream out : sseOutputs.toArray(OutputStream[]::new)) {
            try {
                writeSse(out, frame);
            } catch (IOException e) {
                removeClient(out);
            }
        }
    }

    /** 心跳：向全部 SSE 客户端写注释帧，写失败即摘除死连接。 */
    private void pingAll() {
        for (OutputStream out : sseOutputs.toArray(OutputStream[]::new)) {
            try {
                out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                removeClient(out);
            }
        }
    }

    /**
     * 摘除死连接；全部客户端离场时悬空交互立即 fail-closed
     * （人不在环 = 不批准，ADR-0008 语义延伸，工单 05）。
     */
    private void removeClient(OutputStream out) {
        sseOutputs.remove(out);
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
                    // 错误呈现：非会话事件直推帧（页面渲染 [错误] 卡），不污染会话历史
                    pushEvent(SessionEvent.errorEvent(String.valueOf(e.getMessage())));
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
                // 异常细节仅服务端控制台留痕——错误响应不回显内部消息（M10-02 脱敏）
                System.out.println("[web] 新会话创建失败: " + e);
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
                Session loaded = Session.load(sessionsDir.resolve(id + ".jsonl"));
                bindSession(loaded);
                sessionChangedCallback.accept(loaded);
                byte[] ok = "{\"switched\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, ok.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(ok); }
            } catch (Exception e) {
                // 异常细节（含文件系统路径）仅服务端控制台留痕，不回显给响应体（M10-02 脱敏）
                System.out.println("[web] 会话切换失败: " + e);
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
            System.out.println("[web] /api/answer completed=" + completed);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(resp); }
        });
        // SSE 会话事件流：连接帧 + 存量回放 + 增量广播（断开摘除输出流）
        server.createContext("/api/events", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            sseOutputs.add(out);
            try {
                // 连接帧是 SSE 注释（冒号行），不是 data 帧——前端 JSON.parse 不消费它
                out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                // 存量回放（页面刷新后重放当前会话）+ replay/done 边界帧：
                // 前端以此区分回放 chunk（丢弃）与实时 chunk（流式聚合），增量由 pushEvent 广播
                for (SessionEvent event : session.events()) {
                    writeSse(out, toJson(event));
                }
                writeSse(out, "{\"type\":\"replay/done\"}");
            } catch (Exception e) {
                // 回放中断（含运行时异常）即摘除断连——客户端经 EventSource 重连重新回放
                removeClient(out);
                exchange.close();
            }
        });
    }

    private void writeSse(OutputStream out, String payload) throws IOException {
        out.write(("data: " + payload.replace("\n", "\ndata: ") + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String toJson(SessionEvent event) {
        try {
            return JSON.writeValueAsString(event);
        } catch (IOException e) {
            throw new IllegalStateException("事件 JSON 序列化失败", e);
        }
    }

    /** 侧栏 JSON：会话列表（修改时间倒序，current 标记当前会话）。 */
    private String sessionsJson() {
        var root = JSON.createObjectNode();
        var arr = root.putArray("sessions");
        String currentId = session.id();
        for (Session.SessionSummary summary : Session.list(sessionsDir)) {
            var node = arr.addObject()
                    .put("id", summary.id())
                    .put("lastModifiedMs", summary.lastModifiedMs());
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
            dev.duo.harness.agent.ContextGovernance current = governance;
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

    /** 停止服务与心跳（插件 dispose 调用）。 */
    public void stop() {
        heartbeat.shutdownNow();
        for (OutputStream out : List.copyOf(sseOutputs)) {
            try {
                out.close();
            } catch (IOException ignored) {
                // 连接已死
            }
        }
        sseOutputs.clear();
        server.stop(0);
    }
}
