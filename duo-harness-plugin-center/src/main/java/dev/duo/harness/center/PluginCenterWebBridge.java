package dev.duo.harness.center;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.web.WebRouteRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 插件中心 Web 桥（M35 工单 06）：经端点贡献口（webRoutes，{@code optionalInject}
 * 声明内消费）在 {@code /plugins/center/**} 下挂中心 API——已装/扫描/点名/装/
 * 停/启/卸/挂类路径/改配置。CLI-only 部署（webRoutes 缺席）零感知不挂接。
 *
 * <p>错误语义：业务点名（PluginException/IllegalArgumentException）→ 400 + 消息
 * JSON（页面 toast 可读）；其余异常交路由层兜底 500 + 日志（BUG-20261002-01
 * 铁律，routeHandler 统一包裹）。</p>
 */
public final class PluginCenterWebBridge {

    /** 中心命名空间前缀（/plugins/center/**）。 */
    public static final String CENTER_PREFIX = "center";

    private final PluginCenter center;

    private PluginCenterWebBridge(PluginCenter center) {
        this.center = center;
    }

    /** webRoutes 服务的消费视图（方法名 = 服务名，逐字一致）。 */
    public interface WebRoutesView {
        WebRouteRegistry webRoutes();
    }

    /**
     * webRoutes 在场即挂接中心 API 并返回摘除移除器（调用方挂提供方作用域——
     * 插件拔除即路由摘除）；缺席返回 null（CLI-only 部署）。
     */
    public static Disposable mountIfWebPresent(Context ctx, PluginCenter center) {
        if (!ctx.hasService(WebRouteRegistry.SERVICE_NAME)) {
            return null;
        }
        WebRouteRegistry routes = ctx.as(WebRoutesView.class).webRoutes();
        return new PluginCenterWebBridge(center).mount(routes);
    }

    /** 挂接全部中心端点；任一挂接失败即整体摘除（不留半挂接）。 */
    public Disposable mount(WebRouteRegistry routes) {
        Disposable unmounter = routes.claim(CENTER_PREFIX);
        try {
            routes.mount(CENTER_PREFIX, "api/rows", ex -> guarded(ex, () -> respond(ex, 200, center.status())));
            routes.mount(CENTER_PREFIX, "api/scan", ex -> guarded(ex, () -> {
                // Path 投影为纯字符串（Jackson 对 Path 的默认序列化是 file: URI 形态，
                // 页面回传装回时 Path.of 会炸）
                List<Map<String, Object>> projected = center.scan().stream()
                        .map(p -> Map.<String, Object>of(
                                "path", p.path().toString(),
                                "sizeBytes", p.sizeBytes(),
                                "sha256", p.sha256()))
                        .toList();
                respond(ex, 200, projected);
            }));
            routes.mount(CENTER_PREFIX, "api/inspect", ex -> guarded(ex, () -> {
                String jar = queryParam(ex, "jar");
                if (jar == null || jar.isBlank()) {
                    throw new IllegalArgumentException("缺 jar 查询参数");
                }
                respond(ex, 200, center.inspect(pathFrom(jar)));
            }));
            routes.mount(CENTER_PREFIX, "api/install", ex -> guarded(ex, () -> {
                Map<String, Object> body = readBody(ex);
                center.install(pathFrom((String) body.get("jar")), (String) body.get("id"),
                        (String) body.get("entryFqcn"), mapOf(body.get("config")));
                respond(ex, 200, Map.of("ok", true));
            }));
            routes.mount(CENTER_PREFIX, "api/mount-classpath", ex -> guarded(ex, () -> {
                Map<String, Object> body = readBody(ex);
                center.installClasspath((String) body.get("id"), (String) body.get("fqcn"),
                        mapOf(body.get("config")));
                respond(ex, 200, Map.of("ok", true));
            }));
            routes.mount(CENTER_PREFIX, "api/disable", ex -> rowOp(ex, center::disable));
            routes.mount(CENTER_PREFIX, "api/enable", ex -> rowOp(ex, center::enable));
            routes.mount(CENTER_PREFIX, "api/uninstall", ex -> rowOp(ex, center::uninstall));
            routes.mount(CENTER_PREFIX, "api/reconfigure", ex -> guarded(ex, () -> {
                Map<String, Object> body = readBody(ex);
                center.reconfigure((String) body.get("id"), mapOf(body.get("config")));
                respond(ex, 200, Map.of("ok", true));
            }));
            return unmounter;
        } catch (RuntimeException e) {
            try {
                unmounter.dispose();
            } catch (Exception cleanup) {
                // 摘除失败不掩盖挂接失败主错误（泄漏兜底"需重启生效"同口径）
            }
            throw e;
        }
    }

    /** 业务点名 → 400 + 消息 JSON；其余异常原样上抛交路由层 500 兜底。 */
    private interface IoTask {
        void run() throws IOException;
    }

    private void guarded(HttpExchange ex, IoTask task) throws IOException {
        try {
            task.run();
        } catch (PluginException | IllegalArgumentException e) {
            respondError(ex, e.getMessage());
        }
    }

    private interface RowOperation {
        void run(String id);
    }

    private void rowOp(HttpExchange ex, RowOperation op) throws IOException {
        Map<String, Object> body = readBody(ex);
        String id = (String) body.get("id");
        op.run(id);
        respond(ex, 200, Map.of("ok", true, "id", id));
    }

    // === HTTP 小工具（Jackson 直出；WebHttp 是 web 模块包私有——桥自足） ===

    private static void respond(HttpExchange ex, int status, Object body) throws IOException {
        byte[] payload = body instanceof byte[] b ? b
                : new ObjectMapper().writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(payload);
        }
    }

    /** 业务点名 → 400 + 消息 JSON（页面 toast 直读）。 */
    private static void respondError(HttpExchange ex, String message) throws IOException {
        respond(ex, 400, Map.of("error", message == null ? "请求处理失败" : message));
    }

    /** 路径宽容解析：file: URI 形态（序列化回传）与普通路径都收。 */
    private static Path pathFrom(String raw) {
        if (raw.startsWith("file:")) {
            return Path.of(java.net.URI.create(raw));
        }
        return Path.of(raw);
    }

    private static Map<String, Object> readBody(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (raw.length == 0) {
            throw new IllegalArgumentException("请求体为空（须 JSON：{id, ...}）");
        }
        return new ObjectMapper().readValue(raw, Map.class);
    }

    private static Map<String, Object> mapOf(Object raw) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = raw instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        return map;
    }

    private static String queryParam(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                return URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
