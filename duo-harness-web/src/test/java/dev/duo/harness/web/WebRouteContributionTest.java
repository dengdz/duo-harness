package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 S3（Web HTTP 缝）用例：端点命名空间贡献口（ADR-0037 内核受控口二）——
 * 贡献端点鉴权双态（无 token 403 / 带 token 200 / auth:none 直达）、前缀冲突与
 * 非法前缀点名、claim 移除器摘除后 404 且前缀可复用、处理器异常 500 兜底
 * （BUG-20261002-01 铁律）、未申请先 mount 点名。
 */
class WebRouteContributionTest {

    private static final String TOKEN = "0123456789abcdef-test-token";

    @TempDir
    Path tempDir;

    private WebFace face;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebRouteContributionTest —— 端点贡献口：鉴权双态、冲突/非法前缀点名、"
                + "摘除 404、异常 500 兜底（6 用例） ===");
    }

    @AfterEach
    void tearDown() {
        if (face != null) {
            face.stop();
        }
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface ToolsView {

        ToolsService tools();
    }

    /** 装配：带/不带鉴权令牌的 WebFace（真实 Context + ToolsPlugin；agent 桩不被触达）。 */
    private WebFace start(String authToken) throws IOException {
        Context ctx = Context.root();
        ctx.plugin(new ToolsPlugin(), null).awaitStartup();
        ToolsService tools = ctx.as(ToolsView.class).tools();
        tools.register(ctx, new ToolDefinition() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "回声工具";
            }

            @Override
            public JsonNode parameters() {
                return JsonNodeFactory.instance.objectNode().put("type", "object");
            }

            @Override
            public String execute(ToolExecution execution) {
                return "echo";
            }
        });
        Session session = Session.create(tempDir.resolve("sessions"));
        ChatAgent agent = (userText, listener) -> new AgentReply("ok", List.of(), true);
        face = WebFace.start(0, ctx, tools, session, agent, null, null,
                tempDir.resolve("web-sessions"), 50, null, null, null, authToken);
        return face;
    }

    private HttpResponse<String> get(WebFace target, String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + target.port() + path)).GET();
        if (token != null) {
            builder.header("X-Duo-Token", token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 标准挂接：claim "demo" + mount ping → "pong"。返回 claim 移除器。 */
    private Disposable claimAndMountPing(WebRouteRegistry registry) {
        Disposable unmounter = registry.claim("demo");
        registry.mount("demo", "ping", exchange -> WebHttp.respondText(exchange, 200, "pong"));
        return unmounter;
    }

    // === 用例 ===

    @Test
    void contributedRouteRequiresTokenThenServes() throws Exception {
        WebFace tokenFace = start(TOKEN);
        claimAndMountPing(tokenFace.routes());

        // 栅栏覆盖铁律：无 token 403（fail-closed，与内部端点一致）
        assertEquals(403, get(tokenFace, "/plugins/demo/ping", null).statusCode());
        // 带 token 放行
        HttpResponse<String> ok = get(tokenFace, "/plugins/demo/ping", TOKEN);
        assertEquals(200, ok.statusCode());
        assertEquals("pong", ok.body());
    }

    @Test
    void authNoneServesContributedRouteDirectly() throws Exception {
        WebFace openFace = start(null);
        claimAndMountPing(openFace.routes());

        // auth:none 行为与内部端点一致（直达，无 token 也 200）
        HttpResponse<String> ok = get(openFace, "/plugins/demo/ping", null);
        assertEquals(200, ok.statusCode());
        assertEquals("pong", ok.body());
    }

    @Test
    void duplicatePrefixClaimIsNamed() throws Exception {
        WebFace tokenFace = start(TOKEN);
        tokenFace.routes().claim("demo");

        PluginException e = assertThrows(PluginException.class,
                () -> tokenFace.routes().claim("demo"));

        assertTrue(e.getMessage().contains("/plugins/demo/"), e.getMessage());
        assertTrue(e.getMessage().contains("已被占用"), e.getMessage());
    }

    @Test
    void invalidPrefixIsNamed() throws Exception {
        WebFace tokenFace = start(TOKEN);
        WebRouteRegistry registry = tokenFace.routes();

        assertThrows(PluginException.class, () -> registry.claim(""));
        assertThrows(PluginException.class, () -> registry.claim("a/b"));
        assertThrows(PluginException.class, () -> registry.claim(".."));
        assertThrows(PluginException.class, () -> registry.claim("白名单"));
    }

    @Test
    void unmountMakesRouteDisappearAndPrefixReusable() throws Exception {
        WebFace tokenFace = start(TOKEN);
        WebRouteRegistry registry = tokenFace.routes();
        Disposable unmounter = claimAndMountPing(registry);
        assertEquals(200, get(tokenFace, "/plugins/demo/ping", TOKEN).statusCode());

        // 移除器执行 → 贡献处理器退出调用链（响应不再是 pong——请求落回单页
        // 兜底上下文，404 形态仅在无 / 兜底的部署出现）→ 同前缀可重新申请
        unmounter.dispose();
        HttpResponse<String> after = get(tokenFace, "/plugins/demo/ping", TOKEN);
        assertTrue(!"pong".equals(after.body()), "摘除后贡献处理器不得再被调用: " + after.body());
        assertDoesNotThrow(() -> {
            Disposable re = registry.claim("demo");
            re.dispose();
        });
    }

    @Test
    void handlerExceptionYields500NotBareClose() throws Exception {
        WebFace tokenFace = start(TOKEN);
        WebRouteRegistry registry = tokenFace.routes();
        registry.claim("demo");
        registry.mount("demo", "boom", (HttpExchange exchange) -> {
            throw new IllegalStateException("贡献端点炸了");
        });

        // 兜底铁律（BUG-20261002-01 同款）：异常一律 500 + 文本响应，绝不连接裸关
        HttpResponse<String> response = get(tokenFace, "/plugins/demo/boom", TOKEN);
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("服务端处理失败"), response.body());
    }

    @Test
    void mountWithoutClaimIsNamed() throws Exception {
        WebFace tokenFace = start(TOKEN);

        PluginException e = assertThrows(PluginException.class,
                () -> tokenFace.routes().mount("ghost", "ping",
                        exchange -> WebHttp.respondText(exchange, 200, "pong")));

        assertTrue(e.getMessage().contains("未申请"), e.getMessage());
    }
}
