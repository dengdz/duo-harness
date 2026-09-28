package dev.duo.harness.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import dev.duo.harness.session.SessionEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 响应与请求解析的静态工具（M28 工单 06 从 WebFace 拆出）：响应写入统一走
 * respond 系列，请求体限额与查询参数解析单点——纯函数无状态，端点处理器共用。
 */
final class WebHttp {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WebHttp.class);

    /** JSON 序列化共享实例（ObjectMapper 创建重量级；配置后只读使用线程安全）。 */
    static final ObjectMapper JSON = new ObjectMapper();

    /** POST 请求体大小上限（1MB）：防误粘贴/恶意超大 body 占内存，正常对话文本远低于此。 */
    static final int MAX_BODY_BYTES = 1_000_000;

    private WebHttp() {
    }

    /** 事件序列化（失败上抛 IllegalStateException——广播路径不容静默坏帧）。 */
    static String toJson(SessionEvent event) {
        try {
            return JSON.writeValueAsString(event);
        } catch (IOException e) {
            throw new IllegalStateException("事件 JSON 序列化失败", e);
        }
    }

    /** 统一响应写入：状态 + Content-Type + body（body 为 null = 无体响应）。 */
    static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** 无体响应（错误码形态：400/404/405/413/503 等）。 */
    static void respondEmpty(HttpExchange exchange, int status) throws IOException {
        LOG.debug("空体响应 status={} path={}", status, exchange.getRequestURI().getPath());
        respond(exchange, status, null, null);
    }

    /** 文本响应（text/plain，错误文案形态）。 */
    static void respondText(HttpExchange exchange, int status, String text) throws IOException {
        respond(exchange, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 静态形态响应并禁缓存（`Cache-Control: no-cache`）：单页与脚本随版本频繁演进、
     * 又无 ETag/Last-Modified 可协商，浏览器启发式缓存会让用户拿到旧脚本
     * （实测形态：旧脚本曾渲染出重复的计划卡）；loopback 本地服务重新拉取成本可忽略。
     */
    static void respondNoCache(HttpExchange exchange, int status, String contentType,
                               byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        respond(exchange, status, contentType, body);
    }

    /** JSON 响应（application/json）。 */
    static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        respond(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    /** POST 校验：非 POST 回 405 并返回 false（写端点的统一入口判据）。 */
    static boolean requirePost(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            return true;
        }
        respondEmpty(exchange, 405);
        return false;
    }

    /** 读取请求体并施加大小上限：超限返回 null（调用方回 413），最多读上限+1 字节防内存放大。 */
    static byte[] readBodyLimited(HttpExchange exchange) throws IOException {
        return readBodyLimited(exchange, MAX_BODY_BYTES);
    }

    /** 读取请求体并施加自定义大小上限（附件上传的 base64 膨胀体需要大限额）。 */
    static byte[] readBodyLimited(HttpExchange exchange, long maxBytes) throws IOException {
        int cap = (int) Math.min(maxBytes + 1, Integer.MAX_VALUE);
        byte[] body = exchange.getRequestBody().readNBytes(cap);
        return body.length > maxBytes ? null : body;
    }

    /** 取查询参数原值（缺参返回空串，由调用方解析并决定成败——不做 URL 解码，参数集仅限简单值）。 */
    static String queryParam(HttpExchange exchange, String name) {
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
    static byte[] readClassResource(String path) {
        try (var in = WebHttp.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** 查询参数 URL 解码（UTF-8；非法序列由调用方按 400 处理——搜索词/路径 token 两处共用）。 */
    static String urlDecode(String raw) {
        return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }
}
