package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web 鉴权令牌用例（M24 工单 06，ADR-0026 决策五）：无/错 token 全端点 403
 * （含静态资源与 SSE）；X-Duo-Token 头与 ?token= 查询双通道放行；token=null
 * （auth: none）鉴权关闭。fail-closed 语义：校验失败不给任何端点差异信息。
 */
class WebFaceAuthTest {

    @TempDir
    Path tempDir;

    private static final String TOKEN = "0123456789abcdef-test-token";

    private WebFace face;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebFaceAuthTest —— Web 鉴权令牌：无/错 token 403、头与查询双通道、"
                + "关闭模式（3 用例） ===");
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
        try {
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
        } catch (IOException e) {
            throw e;
        }
    }

    private int statusOf(String pathWithQuery, String tokenHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + face.port() + pathWithQuery));
        if (tokenHeader != null) {
            builder.header("X-Duo-Token", tokenHeader);
        }
        // SSE 端点 body 无限长：ofInputStream 收到响应头即返回，读完状态码即关
        HttpResponse<java.io.InputStream> response =
                client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        response.body().close();
        return response.statusCode();
    }

    @Test
    void missingTokenForbiddenOnEveryEndpoint() throws Exception {
        face = start(TOKEN);
        assertEquals(403, statusOf("/", null), "静态页无 token 403");
        assertEquals(403, statusOf("/api/status", null), "只读端点无 token 403");
        assertEquals(403, statusOf("/api/events", null), "SSE 无 token 403");
        assertEquals(403, statusOf("/api/message", null), "写端点无 token 403");
    }

    @Test
    void tokenViaHeaderAndQueryAcceptedWrongRejected() throws Exception {
        face = start(TOKEN);
        assertEquals(200, statusOf("/api/status", TOKEN), "头通道放行");
        assertEquals(200, statusOf("/?token=" + TOKEN, null), "查询通道放行（首载 URL 形态）");
        assertEquals(200, statusOf("/api/status?token=" + TOKEN, null), "查询通道覆盖 SSE 形态");
        assertEquals(403, statusOf("/api/status", "wrong-token"), "错 token 一律 403");
    }

    @Test
    void indexPageInjectsTokenIntoSubResourceUrls() throws Exception {
        // 阻断回归锁定（三轴审查 Spec 轴）：link/script 子资源不继承父页查询参数——
        // 鉴权开启时服务端必须为 index.html 的子资源 URL 注入 token，否则首载自断
        face = start(TOKEN);
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + face.port() + "/?token=" + TOKEN)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("/web/theme.css?token=" + TOKEN), "样式子资源已注入 token");
        assertTrue(response.body().contains("/web/app.js?token=" + TOKEN), "脚本子资源已注入 token");
        assertEquals(200, statusOf("/web/theme.css?token=" + TOKEN, null), "注入后的子资源请求放行");
    }

    @Test
    void authOffServesWithoutToken() throws Exception {
        face = start(null);
        assertEquals(200, statusOf("/", null), "显式关闭后无 token 照常访问");
        assertEquals(200, statusOf("/api/status", null));
    }
}
