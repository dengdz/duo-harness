package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M38 工单 07 回归锁：/api/session/new 的 workspace 参数——带目录建会话（cwd 锚定
 * 所选目录、绝对化）/ 未带参数回落进程 cwd（CLI 直跑兼容）/ 不存在或非目录 400 点名
 * / 畸形 JSON 400 / 会话列表 currentCwd 字段下发（标题区展示数据源）。
 * 安全口径：目录由用户本机显式选择，档位治理随目录走（与 CLI cd 等价），不做白名单。
 */
class WebSessionWorkspaceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "test-token-abcdef";

    @TempDir
    Path tempDir;

    private WebFace face;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebSessionWorkspaceTest —— session/new 工作区参数：cwd 锚定/回落/400 点名（M38-07） ===");
    }

    @AfterEach
    void tearDown() {
        if (face != null) {
            face.stop();
        }
    }

    interface ToolsView {

        ToolsService tools();
    }

    private WebFace start() throws Exception {
        Session session = Session.create(tempDir.resolve("sessions"));
        Context ctx = Context.root();
        ctx.plugin(new dev.duo.harness.tools.ToolsPlugin(), null).awaitStartup();
        dev.duo.harness.agent.ChatAgent stub = new dev.duo.harness.agent.ChatAgent() {
            @Override
            public dev.duo.harness.agent.AgentReply send(String userText,
                                                         dev.duo.harness.agent.AgentListener listener) {
                return new dev.duo.harness.agent.AgentReply("好的", java.util.List.of(), true);
            }
        };
        face = WebFace.start(0, ctx, ctx.as(ToolsView.class).tools(), session, stub,
                null, null, tempDir.resolve("out"), 50, null, () -> false, null, TOKEN);
        // 生产同构闭包（WebPlugin 318 同款）：带工作区锚定、null 回落进程 cwd
        face.onNewSession(ws -> Session.createDeferred(tempDir.resolve("sessions"),
                ws != null ? ws : dev.duo.harness.core.api.boot.Cwd.path()));
        return face;
    }

    private HttpResponse<String> postNew(String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + face.port() + "/api/session/new"))
                .header("X-Duo-Token", TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void workspaceAnchorsSessionCwdAndEchoesAbsolute() throws Exception {
        start();
        Path ws = tempDir.resolve("proj-a");
        Files.createDirectories(ws);
        HttpResponse<String> res = postNew("{\"workspace\":\"" + ws + "\"}");
        assertEquals(200, res.statusCode(), "带合法工作区 200: " + res.body());
        JsonNode json = MAPPER.readTree(res.body());
        assertEquals(ws.toAbsolutePath().normalize().toString(), json.path("cwd").asText(),
                "响应回显绝对化工作区（会话操作根锚定所选目录）");
        assertTrue(json.path("id").asText().length() > 0, "会话 id 在");
    }

    @Test
    void relativeWorkspaceIsNormalizedToAbsolute() throws Exception {
        start();
        // 相对路径输入按进程 cwd 绝对化（端点统一 normalize 口径）
        HttpResponse<String> res = postNew("{\"workspace\":\".\"}");
        assertEquals(200, res.statusCode());
        assertEquals(dev.duo.harness.core.api.boot.Cwd.path().toString(),
                MAPPER.readTree(res.body()).path("cwd").asText(),
                "相对路径 . 绝对化为进程 cwd");
    }

    @Test
    void noWorkspaceFallsBackToProcessCwd() throws Exception {
        start();
        HttpResponse<String> res = postNew("{}");
        assertEquals(200, res.statusCode(), "未带 workspace 仍 200（CLI 直跑兼容）");
        assertEquals(dev.duo.harness.core.api.boot.Cwd.path().toString(),
                MAPPER.readTree(res.body()).path("cwd").asText(),
                "回落进程 cwd");
    }

    @Test
    void missingDirectoryRejected400() throws Exception {
        start();
        HttpResponse<String> res = postNew("{\"workspace\":\"" + tempDir.resolve("no-such-dir") + "\"}");
        assertEquals(400, res.statusCode(), "目录不存在 400 点名");
        assertTrue(res.body().contains("不存在"), res.body());
    }

    @Test
    void fileAsWorkspaceRejected400() throws Exception {
        start();
        Path file = tempDir.resolve("plain.txt");
        Files.writeString(file, "not a dir");
        HttpResponse<String> res = postNew("{\"workspace\":\"" + file + "\"}");
        assertEquals(400, res.statusCode(), "文件（非目录）400 点名");
        assertTrue(res.body().contains("不存在"), "同条点名消息（目录校验一体化）");
    }

    @Test
    void malformedJsonRejected400() throws Exception {
        start();
        HttpResponse<String> res = postNew("{workspace: not-json");
        assertEquals(400, res.statusCode(), "畸形 JSON 400（不进 500 日志）");
        assertTrue(res.body().contains("error"), res.body());
    }

    @Test
    void sessionsListCarriesCurrentCwd() throws Exception {
        start();
        Path ws = tempDir.resolve("proj-b");
        Files.createDirectories(ws);
        postNew("{\"workspace\":\"" + ws + "\"}");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + face.port() + "/api/sessions"))
                .header("X-Duo-Token", TOKEN).GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        JsonNode json = MAPPER.readTree(res.body());
        assertEquals(ws.toAbsolutePath().normalize().toString(), json.path("currentCwd").asText(),
                "会话列表 currentCwd 下发（标题区展示数据源）");
    }
}
