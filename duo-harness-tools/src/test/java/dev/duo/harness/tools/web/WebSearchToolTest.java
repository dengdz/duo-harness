package dev.duo.harness.tools.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.tools.GuardCheck;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * web_search 全链路测试（seam ①+⑤）：MockWebServer 扮演 Tavily 端点——请求形态
 * （POST/Bearer/query）、归一化渲染、空结果、provider 错误；装配语义经
 * WebToolsPlugin.assemble + 记录型 ToolsService 假件验证（无 key 不注册）。
 */
class WebSearchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MockWebServer server;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebSearchToolTest —— web_search 全链路（Tavily 交互/渲染/装配语义） ===");
        server = new MockWebServer();
    }

    @AfterAll
    static void tearDown() {
        server.stop();
    }

    private static WebSearchTool tool(int maxResults) {
        return new WebSearchTool(new TavilyProvider("test-key", server.baseUrl(), 5_000), maxResults, null);
    }

    private static ToolExecution search(String query) throws Exception {
        return new ToolExecution("web_search", MAPPER.readTree("{\"query\":\"" + query + "\"}"));
    }

    @Test
    void 搜索结果渲染Sources列表并携带鉴权与查询() throws Exception {
        server.respond(MockWebServer.Script.ok("application/json",
                "{\"results\":[{\"title\":\"Tavily 官网\",\"url\":\"https://tavily.com\",\"content\":\"搜索 API\"},"
                        + "{\"title\":\"\",\"url\":\"https://x.com\",\"content\":\"\"}]}"));
        String out = (String) tool(8).execute(search("tavily search api"));

        assertTrue(out.contains("Sources:"), "应有 Sources 节: " + out);
        assertTrue(out.contains("- [Tavily 官网](https://tavily.com) — 搜索 API"), "条目形态: " + out);
        assertTrue(out.contains("- [https://x.com](https://x.com)"), "无标题回退 URL: " + out);
        assertTrue(out.contains("不可信"), "应带不可信声明: " + out);
        assertTrue(out.contains("Markdown 链接"), "应带引用指引: " + out);
        assertEquals("POST", server.lastMethod());
        assertTrue(server.lastPath().endsWith("/search"), "应打 /search 端点");
        assertEquals("Bearer test-key", server.lastHeader("Authorization"), "Bearer 鉴权");
        assertTrue(server.lastBody().contains("\"query\":\"tavily search api\""), "查询应进请求体");
    }

    @Test
    void 空结果明示NoResults() throws Exception {
        server.respond(MockWebServer.Script.ok("application/json", "{\"results\":[]}"));
        String out = (String) tool(8).execute(search("nothing"));
        assertEquals("No results found.", out);
    }

    @Test
    void provider错误原文透出() throws Exception {
        server.respond(MockWebServer.Script.status(429, java.util.Map.of("Content-Type", "application/json"),
                "{\"detail\":\"quota exceeded\"}"));
        RuntimeException e = assertThrows(RuntimeException.class, () -> tool(8).execute(search("q")));
        assertTrue(e.getMessage().contains("429"), "错误应带状态码: " + e.getMessage());
        assertTrue(e.getMessage().contains("quota"), "错误应带服务端原文: " + e.getMessage());
    }

    @Test
    void 缺query结构化报错() throws Exception {
        String out = (String) tool(8).execute(new ToolExecution("web_search", MAPPER.readTree("{}")));
        assertTrue(out.startsWith("[web_search 错误]"));
    }

    @Test
    void 同站点URL变体归一化去重() throws Exception {
        // 回归（验收实测）：http/https、带不带 www 的同一站点只保留首条
        server.respond(MockWebServer.Script.ok("application/json", "{\"results\":["
                + "{\"title\":\"一\",\"url\":\"http://www.tradingkey.com/zh-hans/learn\",\"content\":\"甲\"},"
                + "{\"title\":\"二\",\"url\":\"https://tradingkey.com/zh-hans/learn\",\"content\":\"乙\"},"
                + "{\"title\":\"三\",\"url\":\"https://www.tradingkey.com/zh-hans/learn\",\"content\":\"丙\"},"
                + "{\"title\":\"其他\",\"url\":\"https://other.example.com/a\",\"content\":\"丁\"}]}"));
        String out = (String) tool(8).execute(search("q"));

        assertEquals(1, out.split("tradingkey\\.com", -1).length - 1, "同站点变体应只保留一条: " + out);
        assertTrue(out.contains("- [一]("), "去重保留首条: " + out);
        assertTrue(out.contains("https://other.example.com/a"), "不同站点不受影响: " + out);
    }

    @Test
    void search段缺key不注册_web_search() {
        List<ToolDefinition> registered = new ArrayList<>();
        WebToolsPlugin.assemble(recordingTools(registered), null, WebToolsConfigTestSupport.configWithSearch(""),
                env -> null, null);

        assertTrue(registered.stream().anyMatch(t -> t.name().equals("web_fetch")), "fetch 恒注册");
        assertTrue(registered.stream().noneMatch(t -> t.name().equals("web_search")), "无 key 不注册 search");
    }

    @Test
    void search段有key注册双工具() {
        List<ToolDefinition> registered = new ArrayList<>();
        // 环境变量无解（恒 null）时字面量 key 单独即可生效
        WebToolsPlugin.assemble(recordingTools(registered), null, WebToolsConfigTestSupport.configWithSearch("lit-key"),
                env -> null, null);

        assertTrue(registered.stream().anyMatch(t -> t.name().equals("web_search")), "有字面量 key 注册 search");
    }

    @Test
    void search段缺席只有fetch() {
        List<ToolDefinition> registered = new ArrayList<>();
        WebToolsPlugin.assemble(recordingTools(registered), null, null, env -> "whatever", null);

        assertTrue(registered.stream().anyMatch(t -> t.name().equals("web_fetch")));
        assertTrue(registered.stream().noneMatch(t -> t.name().equals("web_search")), "search 段缺席不注册");
    }

    @Test
    void 非法searchType启动即拒() {
        List<ToolDefinition> registered = new ArrayList<>();
        assertThrows(PluginException.class, () -> WebToolsPlugin.assemble(recordingTools(registered), null,
                WebToolsConfigTestSupport.configWithSearchType("brave"), env -> null, null));
    }

    private static ToolsService recordingTools(List<ToolDefinition> sink) {
        return new ToolsService() {
            @Override
            public Disposable register(dev.duo.harness.core.api.Context registrant, ToolDefinition definition) {
                sink.add(definition);
                return () -> { };
            }

            @Override
            public Disposable guard(dev.duo.harness.core.api.Context registrant, GuardCheck check) {
                return () -> { };
            }

            @Override
            public List<ToolDefinition> list() {
                return List.copyOf(sink);
            }

            @Override
            public ToolResult execute(String toolName, com.fasterxml.jackson.databind.JsonNode args) {
                throw new UnsupportedOperationException("测试假件不执行工具");
            }        };
    }
}
