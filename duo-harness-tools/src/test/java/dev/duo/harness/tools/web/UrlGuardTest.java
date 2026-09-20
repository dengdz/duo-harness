package dev.duo.harness.tools.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF 校验器单测（seam ②，唯一新 seam）：解析器构造注入——全部用假 resolver 与
 * IP 字面量，零真 DNS 零真网络。覆盖字面预检、全地址集"一内一外整体拒绝"、
 * 公网判定各保留段、同源强制。
 */
class UrlGuardTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：UrlGuardTest —— SSRF 三道防线校验件（字面/解析/同源，零真 DNS） ===");
    }

    private static UrlGuard guardFor(InetAddress... addresses) {
        return new UrlGuard(host -> List.of(addresses));
    }

    /** 公网/保留地址便捷构造。 */
    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    void 字面预检_scheme账密超长无效全拒() {
        UrlGuard guard = guardFor();
        assertReject(guard, "ftp://example.com", "http/https");
        assertReject(guard, "http://user:pass@example.com", "用户名/密码");
        assertReject(guard, "https://a.com/" + "x".repeat(2048), "超长");
        assertReject(guard, "not-a-url", "主机名");
    }

    @Test
    void 合法公网URL通过并返回URI() throws Exception {
        URI uri = guardFor(ip("8.8.8.8")).check("http://8.8.8.8/x");
        assertEquals("8.8.8.8", uri.getHost());
    }

    @Test
    void IP字面量保留段全拒() {
        UrlGuard guard = guardFor();
        String[] blocked = {
                "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
                "169.254.1.1", "100.64.0.1", "100.127.255.255", "0.0.0.0", "240.0.0.1",
                "255.255.255.255", "192.0.2.1", "198.51.100.7", "203.0.113.9",
                "[::1]", "[fc00::1]", "[fd12::1]", "[fe80::1]", "[2001:db8::1]"};
        for (String host : blocked) {
            assertReject(guard, "http://" + host + "/x", "非公网");
        }
        // 公网段照常放行
        pass(guard, "http://8.8.8.8/x");
        pass(guard, "http://[2606:4700::1111]/x");
        pass(guard, "http://[2001:4860:4860::8888]/x");
    }

    @Test
    void 全地址集一内一外整体拒绝() throws Exception {
        UrlGuard guard = guardFor(ip("8.8.8.8"), ip("192.168.0.1"));
        assertReject(guard, "http://dual-record.example.com/x", "192.168.0.1");
    }

    @Test
    void 全公网地址集放行() throws Exception {
        UrlGuard guard = guardFor(ip("8.8.8.8"), ip("8.8.4.4"));
        pass(guard, "http://multi.example.com/x");
    }

    @Test
    void 解析失败与空结果拒() {
        UrlGuard empty = new UrlGuard(host -> List.of());
        assertReject(empty, "http://nx.example.com/x", "无结果");

        UrlGuard failing = new UrlGuard(host -> {
            throw new java.io.IOException("nx domain");
        });
        assertReject(failing, "http://err.example.com/x", "DNS 解析失败");
    }

    @Test
    void 同源强制_scheme主机端口全等() throws Exception {
        UrlGuard guard = guardFor(ip("8.8.8.8"));
        URI base = URI.create("http://example.com/x");
        // 相对路径解析后仍指向同一 host（前导 .. 段的消解是目标服务器职责，JDK URI 不代办）
        URI up = guard.checkRedirect(base, "../up");
        assertEquals("example.com", up.getHost());
        assertTrue(up.getPath().endsWith("up"));
        pass(guard.checkRedirect(base, "/y"));
        pass(guard.checkRedirect(base, "http://example.com:80/y"));
        // 跨 scheme / 跨 host / 跨端口 → 拒
        assertRedirectReject(guard, base, "https://example.com/y");
        assertRedirectReject(guard, base, "http://other.com/y");
        assertRedirectReject(guard, base, "http://example.com:8080/y");
    }

    private static void pass(URI ignored) {
        // 可达即通过（checkRedirect 的放行形态无需断言内容）
    }

    private static void pass(UrlGuard guard, String url) {
        guard.check(url);
    }

    private static void assertReject(UrlGuard guard, String url, String reasonPart) {
        UrlGuard.RejectedException e = assertThrows(UrlGuard.RejectedException.class, () -> guard.check(url),
                "应拒绝: " + url);
        assertTrue(e.getMessage().contains(reasonPart),
                "拒绝理由应含「" + reasonPart + "」: " + e.getMessage());
    }

    private static void assertRedirectReject(UrlGuard guard, URI base, String location) {
        UrlGuard.RejectedException e = assertThrows(UrlGuard.RejectedException.class,
                () -> guard.checkRedirect(base, location), "重定向应拒绝: " + location);
        assertTrue(e.getMessage().contains("跨源"), "跨源拒绝应点名: " + e.getMessage());
    }
}
