package dev.duo.harness.web;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final Session session;
    private final Context ctx;
    private final ToolsService tools;
    /** 每个 SSE 连接的会话监听器（连接断开时移除）。 */
    private final List<Disposable> sseSubscriptions = new CopyOnWriteArrayList<>();

    private WebFace(HttpServer server, Session session, Context ctx, ToolsService tools) {
        this.server = server;
        this.session = session;
        this.ctx = ctx;
        this.tools = tools;
    }

    /**
     * 启动并绑定 127.0.0.1:port（port 0 = 系统随机分配，测试用）。
     *
     * @throws IOException 端口绑定失败
     */
    public static WebFace start(int port, Context ctx, ToolsService tools, Session session)
            throws IOException {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(session, "session");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        WebFace face = new WebFace(server, session, ctx, tools);
        face.registerEndpoints();
        server.start();
        return face;
    }

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
        server.createContext("/api/events", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            writeSse(out, ": connected");
            // 先补发存量事件（页面刷新后回放当前会话），再增量推送
            for (SessionEvent event : session.events()) {
                writeSse(out, toJson(event));
            }
            final Disposable[] holder = new Disposable[1];
            holder[0] = session.addListener(event -> {
                try {
                    writeSse(out, toJson(event));
                } catch (IOException e) {
                    // 客户端断开：注销监听并关闭连接（连接已死，异常只影响本连接）
                    removeSubscription(holder[0]);
                    exchange.close();
                }
            });
            sseSubscriptions.add(holder[0]);
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
