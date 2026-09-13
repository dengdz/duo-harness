package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web 双面骨架用例（HttpServer 先例）：起停与 loopback 绑定、静态单页、状态面
 * JSON 形态（插件快照 + 工具清单）、SSE 流——连接首帧、存量回放与实时推送。
 */
class WebFaceTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebFaceTest —— Web 骨架：起停与 loopback 绑定、静态单页、"
                + "状态面 JSON、SSE 连接与事件推送（3 用例） ===");
    }

    interface ToolsView {

        ToolsService tools();
    }

    @TempDir
    Path tempDir;

    private WebFace face;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        if (face != null) {
            face.stop();
        }
    }

    /** 装配：真实 Context + ToolsPlugin（回声工具进清单）+ 指定会话；端口 0 = 随机。 */
    private WebFace start(Session session) throws IOException {
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
                return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("type", "object");
            }

            @Override
            public String execute(ToolExecution execution) {
                return "echo";
            }
        });
        face = WebFace.start(0, ctx, tools, session);
        return face;
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + face.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), path);
        return response.body();
    }

    /** SSE 读线程防护：超时未完成即失败（防挂死）。 */
    private static String readWithTimeout(Future<String> readFuture) throws Exception {
        return readFuture.get(10, TimeUnit.SECONDS);
    }

    @Test
    void servesStaticPageOnRoot() throws Exception {
        start(Session.create(tempDir.resolve("sessions")));
        String body = get("/");
        assertTrue(body.contains("duo-harness"), "静态单页可达");
    }

    @Test
    void statusJsonContainsSnapshotsAndTools() throws Exception {
        start(Session.create(tempDir.resolve("sessions")));
        String body = get("/api/status");
        JsonNode json = new ObjectMapper().readTree(body);
        assertEquals("ACTIVE", json.path("plugins").get(0).path("state").asText(), "ToolsPlugin ACTIVE");
        assertTrue(json.path("tools").toString().contains("echo"), "工具清单含 echo");
    }

    @Test
    void sseReplaysHistoryAndPushesLive() throws Exception {
        // 存量事件先行落盘（连接后回放），再实时推送（BUG-20260913-03 防线的 Web 面）
        Session session = Session.create(tempDir.resolve("sessions"));
        session.append(SessionEvent.userMessage("历史消息"));
        start(session);

        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + face.port() + "/api/events")).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));

        CompletableFuture<String> replayAndLive = CompletableFuture.supplyAsync(() -> {
            try {
                String connected = reader.readLine();
                String blank = reader.readLine();
                String replay = reader.readLine();
                session.append(SessionEvent.userMessage("实时消息"));
                String blank2 = reader.readLine();
                String live = reader.readLine();
                return connected + "|" + blank + "|" + replay + "|" + blank2 + "|" + live;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        String result = readWithTimeout(replayAndLive);

        assertTrue(result.startsWith("data: : connected"), result);
        assertTrue(result.contains("历史消息"), "存量事件回放: " + result);
        assertTrue(result.contains("实时消息"), "实时事件推送: " + result);
    }
}
