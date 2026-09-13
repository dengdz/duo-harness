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
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Web 双面呈现位（M8）：JDK 内置 HttpServer 承载的本地服务——静态单页（双区布局）、
 * `/api/status`（插件快照 + 工具清单 JSON）、`/api/events`（SSE 会话事件流）。
 *
 * <p>只绑定 127.0.0.1（ADR-0007 v3 安全基线，鉴权 M9+）；执行器用虚拟线程
 * （每任务一线程，SSE 长连接不占平台线程，ADR-0002 同源）。会话事件经
 * {@link Session#addListener} 订阅，每个 SSE 连接独立订阅、断开即注销。</p>
 */
public final class WebFace {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final Context ctx;
    private final ToolsService tools;
    private final List<Disposable> sseSubscriptions = new CopyOnWriteArrayList<>();
    /** HITL Web answerer（工单 05）：待答请求经 SSE 推送，POST /api/answer 完成。 */
    private final WebAnswerer webAnswerer;
    /** 可换会话（/new 等价）：换绑时 SSE 监听器随之迁移。 */
    private volatile Session session;
    /** 对话执行者（工单 04 装配；null = 对话面未就绪）。 */
    private volatile ChatAgent agent;
    /** 单飞标志：一次只跑一轮 send（CLI 单入口同约定）。 */
    private final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean(false);

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
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        WebFace face = new WebFace(server, ctx, tools, session, webAnswerer);
        face.bindSession(session);
        face.agent = agent;
        face.registerEndpoints();
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
                // 旧会话监听器注销失败无碍：新订阅已就位
            }
        }
        sseSubscription = session.addListener(this::pushEvent);
    }
    private Disposable sseSubscription;
    /** 新会话供给者（装配层提供——/new 每次给全新会话，agent 随之重建）。 */
    private volatile java.util.function.Supplier<Session> newSessionSupplier =
            () -> { throw new IllegalStateException("新会话供给者未装配"); };
    /** 新会话回调（装配层重建 agent 并换绑会话）。 */
    private volatile Runnable newSessionCallback;
    void onNewSession(java.util.function.Supplier<Session> supplier, java.util.function.Consumer<Session> onCreated) {
        this.newSessionSupplier = java.util.Objects.requireNonNull(supplier, "supplier");
        this.newSessionCallback = () -> onCreated.accept(newSessionSupplier.get());
    }
    /** 装配层触发：开新会话（换绑事件流 + 回调重建 agent）。 */
    private void newSession() {
        bindSession(newSessionSupplier.get());
        if (newSessionCallback != null) {
            newSessionCallback.run();
        }
    }

    /** 测试与装配层用：替换对话执行者（/new 重建后调用）。 */
    void setAgent(ChatAgent agent) {
        this.agent = agent;
    }

    /** 当前会话（装配层重建 agent 时取用）。 */
    Session currentSession() {
        return session;
    }

    /** SSE 帧推送：单个会话事件 → data 帧（断连由写异常路径处理）。 */
    private void pushEvent(SessionEvent event) {
        boolean anyAlive = false;
        for (var out : sseOutputs.toArray(OutputStream[]::new)) {
            try {
                writeSse(out, toJson(event));
                anyAlive = true;
            } catch (IOException e) {
                // 客户端断开：关闭连接，输出流由调用处清理
                try { out.close(); } catch (IOException ignored) { }
                sseOutputs.remove(out);
            }
        }
        // 全部 SSE 客户端断开（页面离开）→ 悬空交互立即 fail-closed（ADR-0008）
        if (!anyAlive && sseOutputs.isEmpty() && webAnswerer != null) {
            webAnswerer.failClosedAll();
        }
    }
    private final List<OutputStream> sseOutputs = new CopyOnWriteArrayList<>();

    private void registerEndpoints() {
        server.createContext("/", exchange -> {
            byte[] page = readClasspage();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(page);
            }
        });
        server.createContext("/api/status", exchange -> {
            byte[] body = statusJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
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
            if (agent == null) {
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
                    // Web 面只补 assistant/chunk 的会话事件（tool/call、tool/result、assistant/message 由 agent 自身追加）
                    agent.send(text, new dev.duo.harness.agent.AgentListener() {
                        @Override
                        public void onChunk(String chunk) {
                            session.append(SessionEvent.assistantChunk(chunk));
                        }
                    });
                } catch (Exception e) {
                    // 错误呈现：非会话事件的直推帧（页面渲染 [错误] 卡），不污染会话历史
                    pushEvent(SessionEvent.errorEvent(String.valueOf(e.getMessage())));
                } finally {
                    busy.set(false);
                }
            });
        });
        server.createContext("/api/session/new", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            // 新会话：换绑事件流 + 通知装配层重建 agent（sessionSupplier 回调）
            try {
                newSession();
            } catch (Exception e) {
                byte[] msg = ("新会话创建失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
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
        server.createContext("/api/events", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            sseOutputs.add(out);
            try {
                writeSse(out, ": connected");
                // 先补发存量事件（页面刷新后回放当前会话），增量由 pushEvent 广播
                for (SessionEvent event : session.events()) {
                    writeSse(out, toJson(event));
                }
            } catch (IOException e) {
                sseOutputs.remove(out);
                webAnswerer.failClosedAll();
                exchange.close();
            }
        });
    }

    private void removeSubscription(Disposable subscription) {
        sseSubscriptions.remove(subscription);
        try {
            subscription.dispose();
        } catch (Exception ignored) {
            // 注销失败无碍：连接已死
        }
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

    /** 实际绑定端口（构造传 0 时为系统分配值；SSE 推送 JSON 序列化需要）。 */
    public int port() {
        return server.getAddress().getPort();
    }

    /** 当前活跃的 SSE 连接数（观测用）。 */
    public int sseConnections() {
        return sseSubscriptions.size();
    }

    /** 停止服务（插件 dispose 调用）。 */
    public void stop() {
        for (Disposable subscription : List.copyOf(sseSubscriptions)) {
            removeSubscription(subscription);
        }
        server.stop(0);
    }
}
