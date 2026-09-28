package dev.duo.harness.web;

import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.io.IOException;

/**
 * 入口栅栏（M28 工单 06 从 WebFace 拆出；M16 工单 02，术语"入口栅栏"）：三级校验——
 * ① 全请求 Host 头必须在白名单内（127.0.0.1 / localhost / [::1] 带本服务端口）：
 * DNS rebinding 攻击把恶意域名解析到 127.0.0.1，浏览器自动带的 Host 头是
 * 攻击域名而非回环地址，白名单直接封死；缺失也拒（fail-closed）。
 * ② 写端点（POST）额外校验 Origin：缺席（curl/本地脚本）或同源放行，非空且
 * 不同源 → 403——浏览器发起的跨站 POST 必带 Origin，拦它即拦 CSRF；
 * GET/SSE 无副作用不校验 Origin，Host 校验已兜底。无配置开关：白名单随
 * 绑定地址派生，未来 bind 配置化时一并放宽。
 * ③ 鉴权令牌（M24 工单 06，ADR-0026 决策五；authToken 非 null 时启用）：请求须
 * 携 {@code X-Duo-Token} 头或 {@code ?token=} 查询参数（SSE 通道），常量时间
 * 比对，失败一律 403——静态资源同样受检（首载经带 token 的 URL）。
 */
final class WebEntryGate {

    /** 入口栅栏 Host 白名单（按绑定端口生成）：回环地址 + 本服务端口。 */
    private final java.util.Set<String> allowedHosts;
    /** 入口栅栏 Origin 白名单（同源形态：http + 回环地址 + 本服务端口）。 */
    private final java.util.Set<String> allowedOrigins;
    /** 鉴权令牌（M24 工单 06；null = 鉴权关闭——测试与嵌入用途，产品装配恒传）。 */
    private final String authToken;

    WebEntryGate(int port, String authToken) {
        String portText = Integer.toString(port);
        this.allowedHosts = java.util.Set.of(
                "127.0.0.1:" + portText, "localhost:" + portText, "[::1]:" + portText);
        this.allowedOrigins = java.util.Set.of(
                "http://127.0.0.1:" + portText, "http://localhost:" + portText,
                "http://[::1]:" + portText);
        this.authToken = authToken;
    }

    /** 鉴权令牌（null = 鉴权关闭；单页注入与 URL 拼装用）。 */
    String authToken() {
        return authToken;
    }

    /** 三级校验（Host → POST Origin → 令牌）；通过返回 true，未过即已写响应。 */
    boolean admits(HttpExchange exchange) throws IOException {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || !allowedHosts.contains(host.strip().toLowerCase(java.util.Locale.ROOT))) {
            WebHttp.respondEmpty(exchange, 403);
            return false;
        }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.isBlank()
                    && !allowedOrigins.contains(origin.strip().toLowerCase(java.util.Locale.ROOT))) {
                WebHttp.respondEmpty(exchange, 403);
                return false;
            }
        }
        if (authToken != null && !tokenMatches(exchange)) {
            WebHttp.respondEmpty(exchange, 403);
            return false;
        }
        return true;
    }

    /** 鉴权令牌校验：X-Duo-Token 头或 ?token= 查询参数，常量时间比对防时序侧信道。 */
    private boolean tokenMatches(HttpExchange exchange) throws IOException {
        String candidate = exchange.getRequestHeaders().getFirst("X-Duo-Token");
        if (candidate == null || candidate.isBlank()) {
            candidate = WebHttp.queryParam(exchange, "token");
        }
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                candidate.strip().getBytes(StandardCharsets.UTF_8),
                authToken.getBytes(StandardCharsets.UTF_8));
    }
}
