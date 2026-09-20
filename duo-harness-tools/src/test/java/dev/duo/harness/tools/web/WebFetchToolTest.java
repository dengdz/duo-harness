package dev.duo.harness.tools.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.tools.ToolExecution;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * web_fetch 工具直调测试（seam ①）：经 MockWebServer 供靶验证行为面——
 * 渲染格式（头行/不可信声明/正文/截断 footer）、3xx 结果渲染、超时错误回填、
 * User-Agent 与请求形态。零真实外网。
 */
class WebFetchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MockWebServer server;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebFetchToolTest —— web_fetch 行为面（渲染/截断/3xx/超时/请求形态） ===");
        server = new MockWebServer();
    }

    @AfterAll
    static void tearDown() {
        server.stop();
    }

    /** 测试 resolver：把 localhost 报成公网地址——mock 服务器在回环上，但校验面按公网放行。 */
    private static UrlGuard testGuard() throws Exception {
        return new UrlGuard(host -> java.util.List.of(java.net.InetAddress.getByName("93.184.216.34")));
    }

    private static WebFetchTool tool() throws Exception {
        return new WebFetchTool(WebToolsConfig.defaults(), testGuard(), null);
    }

    private static WebFetchTool toolWith(int timeoutMs, int maxOutputChars) throws Exception {
        return new WebFetchTool(new WebToolsConfig(timeoutMs, 5_000_000, 100_000, maxOutputChars, 5,
                WebToolsConfig.DEFAULT_USER_AGENT), testGuard(), null);
    }

    private static ToolExecution fetch(String url) throws Exception {
        return new ToolExecution("web_fetch", MAPPER.readTree("{\"url\":\"" + url + "\"}"));
    }

    @Test
    void happyPath渲染头行声明与Markdown正文() throws Exception {
        server.respond(MockWebServer.Script.ok("text/html; charset=utf-8",
                "<html><head><title>t</title><script>evil()</script></head>"
                        + "<body><h1>标题一</h1><p>正文段落。<span>行内</span></p></body></html>"));
        String out = (String) tool().execute(fetch(server.baseUrl() + "/doc"));

        assertTrue(out.startsWith("Fetched " + server.baseUrl() + "/doc (HTTP 200)\n"),
                "头行应为最终 URL + 状态码: " + out);
        assertTrue(out.contains("不可信"), "应带不可信数据声明: " + out);
        assertTrue(out.contains("# 标题一"), "标题应转 Markdown: " + out);
        assertTrue(out.contains("正文段落"), "段落文本应保留: " + out);
        assertFalse(out.contains("evil()"), "script 内容应被剔除: " + out);
    }

    @Test
    void 缺参与非法URL为结构化错误() throws Exception {
        String missing = (String) tool().execute(new ToolExecution("web_fetch", MAPPER.readTree("{}")));
        assertTrue(missing.startsWith("[web_fetch 错误]"), "缺 url 应结构化报错: " + missing);

        String noHost = (String) tool().execute(fetch("not-a-url"));
        assertTrue(noHost.startsWith("[web_fetch 错误]"), "无 scheme/host 应结构化报错: " + noHost);

        String badChar = (String) tool().execute(fetch("http://bad url with space"));
        assertTrue(badChar.startsWith("[web_fetch 错误]"), "含非法字符应结构化报错: " + badChar);
    }

    @Test
    void 输出超上限截断并追加Footer() throws Exception {
        server.respond(MockWebServer.Script.ok("text/html", "<p>" + "很长的正文。".repeat(200) + "</p>"));
        String out = (String) toolWith(30_000, 400).execute(fetch(server.baseUrl() + "/long"));

        assertTrue(out.contains("Content truncated"), "截断应附 footer: " + out);
        assertTrue(out.length() < 1_500, "输出应被截短: " + out.length());
    }

    @Test
    void 响应超时抛错点名时长() {
        server.respond(new MockWebServer.Script(200, Map.of("Content-Type", "text/html"),
                "<p>slow</p>".getBytes(java.nio.charset.StandardCharsets.UTF_8), 2_000));
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> toolWith(300, 200_000).execute(fetch(server.baseUrl() + "/slow")));
        assertTrue(e.getMessage().contains("超时"), "超时错误应点名: " + e.getMessage());
    }

    @Test
    void 请求携带可配UserAgent与GET方法() throws Exception {
        server.respond(MockWebServer.Script.ok("text/html", "<p>x</p>"));
        tool().execute(fetch(server.baseUrl() + "/ua-check"));

        assertEquals("GET", server.lastMethod(), "应为 GET 请求");
        assertEquals("/ua-check", server.lastPath());
        assertEquals(WebToolsConfig.DEFAULT_USER_AGENT, server.lastHeader("User-Agent"),
                "User-Agent 应可配且缺省 duo-harness/版本");
    }

    @Test
    void 回环字面量目标被拒() throws Exception {
        String out = (String) tool().execute(fetch("http://127.0.0.1:" + 1 + "/x"));
        assertTrue(out.startsWith("[web_fetch 错误]"), "回环地址应被拒: " + out);
        assertTrue(out.contains("非公网"), "拒绝理由应点名非公网: " + out);
    }

    @Test
    void 同源重定向逐跳跟随() throws Exception {
        server.respondInOrder(
                MockWebServer.Script.status(302, Map.of("Location", "/moved"), ""),
                MockWebServer.Script.ok("text/html", "<p>moved content</p>"));
        String out = (String) tool().execute(fetch(server.baseUrl() + "/old"));

        assertTrue(out.startsWith("Fetched " + server.baseUrl() + "/moved (HTTP 200)\n"),
                "头行应为最终地址: " + out);
        assertTrue(out.contains("moved content"));
        assertEquals(2, server.requestCount(), "应恰好两跳");
    }

    @Test
    void 跨源重定向被拒并指引直接抓取() throws Exception {
        String otherHostBase = server.baseUrl().replace("localhost", "127.0.0.1");
        server.respond(MockWebServer.Script.status(302,
                Map.of("Location", otherHostBase + "/elsewhere"), ""));
        String out = (String) tool().execute(fetch(server.baseUrl() + "/old"));

        assertTrue(out.startsWith("[web_fetch 错误]"), "跨源重定向应被拒: " + out);
        assertTrue(out.contains("直接抓取"), "应指引模型直接抓取: " + out);
    }

    @Test
    void 重定向超跳数报错() throws Exception {
        server.respondInOrder(
                MockWebServer.Script.status(302, Map.of("Location", "/b"), ""),
                MockWebServer.Script.status(302, Map.of("Location", "/c"), ""),
                MockWebServer.Script.ok("text/html", "<p>c</p>"));
        WebFetchTool oneHop = new WebFetchTool(new WebToolsConfig(30_000, 5_000_000, 100_000, 200_000, 1,
                WebToolsConfig.DEFAULT_USER_AGENT), testGuard(), null);
        String out = (String) oneHop.execute(fetch(server.baseUrl() + "/a"));

        assertTrue(out.startsWith("[web_fetch 错误]"), "超跳数应报错: " + out);
        assertTrue(out.contains("1 跳"), "应点名上限: " + out);
    }

    private static WebFetchTool fetchTool(long maxResponseBytes) throws Exception {
        return new WebFetchTool(new WebToolsConfig(30_000, maxResponseBytes, 100_000, 200_000, 5,
                WebToolsConfig.DEFAULT_USER_AGENT), testGuard(), null);
    }

    @Test
    void 二进制与缺失ContentType拒绝() throws Exception {
        server.respond(MockWebServer.Script.ok("image/png", "not really png"));
        String image = (String) tool().execute(fetch(server.baseUrl() + "/img"));
        assertTrue(image.startsWith("[web_fetch 错误]"), "图片应拒绝: " + image);
        assertTrue(image.contains("内容类型"), "应点名内容类型: " + image);

        server.respond(MockWebServer.Script.status(200, Map.of(), "<p>no type</p>"));
        String noType = (String) tool().execute(fetch(server.baseUrl() + "/none"));
        assertTrue(noType.contains("Content-Type"), "缺失 Content-Type 应点名: " + noType);
    }

    @Test
    void 非HTML文本透传不转换() throws Exception {
        server.respond(MockWebServer.Script.ok("application/json", "{\"k\":\"v\",\"n\":1}"));
        String out = (String) tool().execute(fetch(server.baseUrl() + "/api"));

        assertTrue(out.contains("\"k\":\"v\""), "JSON 应原样透传: " + out);
        assertFalse(out.contains("# "), "透传不做 Markdown 转换");
    }

    @Test
    void 未知字符集拒绝不解码为乱码() throws Exception {
        server.respond(MockWebServer.Script.ok("text/html; charset=x-unknown-charset", "<p>x</p>"));
        String out = (String) tool().execute(fetch(server.baseUrl() + "/weird"));
        assertTrue(out.contains("未知字符集"), "未知 charset 应点名拒绝: " + out);
    }

    @Test
    void 非2xx错误页作为结果渲染() throws Exception {
        server.respond(MockWebServer.Script.status(404, Map.of("Content-Type", "text/html"),
                "<html><body><h1>Not Found</h1></body></html>"));
        String out = (String) tool().execute(fetch(server.baseUrl() + "/missing"));

        assertTrue(out.contains("(HTTP 404)"), "头行带状态码: " + out);
        assertTrue(out.contains("Not Found"), "错误页正文照常给出: " + out);
        assertFalse(out.startsWith("[web_fetch 错误]"), "非 2xx 不是错误形态");
    }

    @Test
    void 响应体超字节上限直接错误() throws Exception {
        server.respond(MockWebServer.Script.ok("text/plain", "x".repeat(500)));
        String out = (String) fetchTool(100).execute(fetch(server.baseUrl() + "/big"));

        assertTrue(out.contains("响应体过大"), "Content-Length 预检应拦截: " + out);
        assertFalse(out.contains("xxx"), "超限不给部分内容: " + out);
    }

    @Test
    void 恰好填满字节上限不算截断() throws Exception {
        String exact = "y".repeat(100);
        server.respond(MockWebServer.Script.ok("text/plain", exact));
        String out = (String) fetchTool(100).execute(fetch(server.baseUrl() + "/exact"));

        assertTrue(out.contains(exact), "恰好填满应完整给出: " + out);
        assertFalse(out.contains("Content truncated"), "恰好填满不算截断: " + out);
    }

    @Test
    void 深嵌套页面返回占位符() throws Exception {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            deep.append("<div>");
        }
        deep.append("<p>deep text</p>");
        for (int i = 0; i < 600; i++) {
            deep.append("</div>");
        }
        server.respond(MockWebServer.Script.ok("text/html", deep.toString()));
        String out = (String) tool().execute(fetch(server.baseUrl() + "/deep"));

        assertTrue(out.contains(HtmlToMarkdown.UNCONVERTIBLE_PLACEHOLDER), "超深应返回占位符: " + out);
        assertFalse(out.contains("deep text"), "超深不灌正文: " + out);
    }

    @Test
    void head重量级页面仍取到正文() throws Exception {
        // 回归（验收实测 baeldung）：head 内联样式超过正文字符上限时，转换前预切会把
        // <body> 整段切掉得到空正文——限额后置后正文必须还在
        String headJunk = "a".repeat(120_000);
        server.respond(MockWebServer.Script.ok("text/html",
                "<html><head><style>.x{color:" + headJunk + "}</style></head>"
                        + "<body><p>正文关键内容</p></body></html>"));
        String out = (String) tool().execute(fetch(server.baseUrl() + "/headheavy"));

        assertTrue(out.contains("正文关键内容"), "head 重量级页面正文不应丢失: " + out);
    }
}
