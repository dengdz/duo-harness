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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Web 双面呈现位（M8）：JDK 内置 HttpServer 承载的本地服务——静态单页（双区布局）、
 * `/api/status`（插件快照 + 工具清单 JSON）、`/api/events`（SSE 会话事件流）、
 * `/api/message`（对话入口，虚拟线程异步执行 agent.send）、`/api/session/new`（开新会话）、
 * `/api/answer`（HITL 回答完成，接 M6 交互 seam 的 Web answerer）。
 *
 * <p>只绑定 127.0.0.1（ADR-0007 v3 安全基线，鉴权 M9+）；执行器用虚拟线程
 * （每任务一线程，SSE 长连接不占平台线程，ADR-0002 同源）。SSE 连接带 15s
 * 心跳帧保活（写失败即摘除死连接，兼防代理静默断连）；事件经会话监听器广播；
 * 全部客户端断开时悬空交互 fail-closed（经 {@link WebAnswerer}，ADR-0008 延伸）。</p>
 */
public final class WebFace {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;

    private final HttpServer server;
    private final Context ctx;
    private final ToolsService tools;
    /** SSE 客户端输出流（多客户端广播，心跳写失败即摘除）。 */
    private final CopyOnWriteArrayList<OutputStream> sseOutputs = new CopyOnWriteArrayList<>();
    /** HITL Web answerer（审批/提问的 Web 呈现位）。 */
    private final WebAnswerer webAnswerer;
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
    /** 新会话回调（装配层重建 agent）。 */
    private volatile Runnable newSessionCallback;

    private WebFace(HttpServer server, Context ctx, ToolsService tools, Session session,
                    WebAnswerer webAnswerer) {
        this.server = server;
        this.ctx = ctx;
        this.tools = tools;
        this.session = session;
        this.webAnswerer = webAnswerer;
    }

    /**
     * 启动并绑定 127.0.0.1:port（port 0 = 系统随机分配，测试用）。
     *
     * @throws IOException 端口绑定失败
     */
    public static WebFace start(int port, Context ctx, ToolsService tools, Session session,
                                ChatAgent agent, WebAnswerer webAnswerer) throws IOException {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(agent, "agent");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        WebFace face = new WebFace(server, ctx, tools, session, webAnswerer);
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

    /** 换绑会话并通知装配层重建 agent（/new 语义）。 */
    private void newSession() {
        bindSession(newSessionSupplier.get());
        if (newSessionCallback != null) {
            newSessionCallback.run();
        }
    }

    /** 注册 /new 的供给者与回调（装配层接线；供 WebPlugin 调用，包级可见）。 */
    void onNewSession(java.util.function.Supplier<Session> supplier,
                      java.util.function.Consumer<Session> onCreated) {
        this.newSessionSupplier = java.util.Objects.requireNonNull(supplier, "supplier");
        this.newSessionCallback = () -> onCreated.accept(newSessionSupplier.get());
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
                sseOutputs.remove(out);
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
                sseOutputs.remove(out);
            }
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
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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
        // 开新会话：换绑事件流 + 通知装配层重建 agent
        server.createContext("/api/session/new", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            newSession();
            byte[] body = ("{\"id\":\"" + session.id() + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        // HITL 回答端点：{approved: bool} 或 {values: ["..."]} → 完成 WebAnswerer 悬空请求
        server.createContext("/api/answer", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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
                writeSse(out, ": connected");
                // 存量回放（页面刷新后重放当前会话），增量由 pushEvent 广播
                for (SessionEvent event : session.events()) {
                    writeSse(out, toJson(event));
                }
            } catch (IOException e) {
                sseOutputs.remove(out);
                exchange.close();
            }
        });
    }

    private void removeSseOutput(OutputStream out) {
        sseOutputs.remove(out);
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

    /** 状态面 JSON：插件快照 + 工具清单。 */
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
            return root.toString();
        } catch (Exception e) {
            throw new IllegalStateException("状态面 JSON 构建失败", e);
        }
    }

    private byte[] readClasspage() {
        try (var in = WebFace.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                return "<html><body><p>web/index.html 资源缺失</p></body></html>".getBytes(StandardCharsets.UTF_8);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            return "<html><body><p>页面读取失败</p></body></html>".getBytes(StandardCharsets.UTF_8);
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
