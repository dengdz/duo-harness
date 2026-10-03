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
 * 呈现贡献聚合端点用例（M36 工单 03，S2 HTTP 缝）：/api/presentation 快照下发
 * （主题值集 + 展示卡声明）、入口栅栏覆盖（无 token 403 fail-closed、auth: none
 * 行为一致）、注册即见（快照读贡献口现值）。
 */
class PresentationEndpointTest {

    private static final String TOKEN = "0123456789abcdef-test-token";

    @TempDir
    Path tempDir;

    private WebFace face;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：PresentationEndpointTest —— 呈现聚合端点：快照下发/栅栏覆盖/"
                + "auth 关闭一致（3 用例） ===");
    }

    @AfterEach
    void tearDown() {
        if (face != null) {
            face.stop();
        }
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
        face.presentation().claimTheme(new PresentationRegistry.ThemeDeclaration(
                "test-dark", "测试暗色", java.util.Map.of(
                "--bg", "#0b0f14",
                "--accent", "rgba(86,134,254,1)")));
        face.presentation().registerCard(new PresentationRegistry.CardDeclaration(
                "web_search", "globe", "网页搜索", List.of("query")));
        return face;
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface ToolsView {

        ToolsService tools();
    }

    private HttpResponse<String> get(String pathWithQuery, String tokenHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + face.port() + pathWithQuery));
        if (tokenHeader != null) {
            builder.header("X-Duo-Token", tokenHeader);
        }
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void snapshotServesRegisteredThemesAndCardsThroughGate() throws Exception {
        start(TOKEN);

        HttpResponse<String> denied = get("/api/presentation", null);
        assertEquals(403, denied.statusCode(), "无 token 一律 403（fail-closed，栅栏覆盖贡献口同源端点）");

        HttpResponse<String> ok = get("/api/presentation?token=" + TOKEN, null);
        assertEquals(200, ok.statusCode());
        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(ok.body());
        assertEquals("test-dark", body.path("themes").get(0).path("id").asText());
        assertEquals("#0b0f14", body.path("themes").get(0).path("tokens").path("--bg").asText());
        assertEquals("rgba(86,134,254,1)", body.path("themes").get(0).path("tokens")
                .path("--accent").asText(), "函数形态字面量（rgba）原样下发");
        assertEquals("web_search", body.path("cards").get(0).path("toolName").asText());
        assertEquals("globe", body.path("cards").get(0).path("icon").asText());
        assertEquals("query", body.path("cards").get(0).path("summaryFields").get(0).asText());
    }

    @Test
    void wrongTokenIsForbiddenToo() throws Exception {
        start(TOKEN);

        HttpResponse<String> wrong = get("/api/presentation?token=wrong-token", null);
        assertEquals(403, wrong.statusCode(), "错 token 同样 403（不给端点差异信息）");
    }

    @Test
    void authClosedServesDirectly() throws Exception {
        start(null);

        HttpResponse<String> ok = get("/api/presentation", null);
        assertEquals(200, ok.statusCode(), "auth: none 与既有端点行为一致（直连 200）");
        assertTrue(ok.body().contains("test-dark"), ok.body());
    }
}
