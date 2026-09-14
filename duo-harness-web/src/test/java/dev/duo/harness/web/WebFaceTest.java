package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.ChatAgent;
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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web 双面骨架用例（HttpServer 先例）：起停与 loopback 绑定、静态单页、状态面
 * JSON、SSE 流（存量回放 + 实时推送）、对话面（POST /api/message → agent 执行 →
 * 事件入会话经 SSE 呈现）、/new 端点。
 */
class WebFaceTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebFaceTest —— Web 骨架：起停绑定、静态页、状态 JSON、SSE、"
                + "对话面（POST 发送→事件入会话→SSE）、/new（5 用例） ===");
    }

    interface ToolsView {

        ToolsService tools();
    }

    @TempDir
    Path tempDir;

    private WebFace face;
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<SessionEvent> received = new CopyOnWriteArrayList<>();
    /** 会话变更回调的记录（/new 与 /switch 都应回调——分脑防御，BUG-20260914-02）。 */
    private final List<Session> changedSessions = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        if (face != null) {
            face.stop();
        }
    }

    /** 装配：真实 Context + ToolsPlugin（回声工具进清单）+ 指定会话与 agent；端口 0 = 随机。 */
    private WebFace start(Session session, ChatAgent agent) throws IOException {
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
        face = WebFace.start(0, ctx, tools, session, agent, null,
                tempDir.resolve("web-sessions"));
        // 会话变更接线：/new 与 /switch 换绑后回调（装配层职责，骨架用例记录变更）
        face.onNewSession(() -> Session.create(tempDir.resolve("web-sessions")));
        face.onSessionChanged(changed -> changedSessions.add(changed));
        return face;
    }

    private HttpResponse<String> post(String path, String jsonBody) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + face.port() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = fetch(path);
        assertEquals(200, response.statusCode(), path);
        return response.body();
    }

    private HttpResponse<String> fetch(String path) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + face.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void servesStaticPageOnRoot() throws Exception {
        Session session = Session.create(tempDir.resolve("sessions"));
        start(session, scriptedAgent(session, "ok"));
        String body = get("/");
        assertTrue(body.contains("duo-harness"), "静态单页可达");
    }

    @Test
    void servesSplitStaticAssets() throws Exception {
        // 拆分三件（工单 M10-01）：样式与脚本独立文件，经 /web/ 前缀白名单可达
        Session session = Session.create(tempDir.resolve("sessions"));
        start(session, scriptedAgent(session, "ok"));

        HttpResponse<String> css = fetch("/web/theme.css");
        assertEquals(200, css.statusCode(), "theme.css 可达");
        assertTrue(css.headers().firstValue("Content-Type").orElse("").contains("text/css"), "css 类型标记");
        assertTrue(css.body().contains("--brand"), "设计 token 在 theme.css");

        HttpResponse<String> js = fetch("/web/app.js");
        assertEquals(200, js.statusCode(), "app.js 可达");
        assertTrue(js.headers().firstValue("Content-Type").orElse("").contains("javascript"), "js 类型标记");
        assertTrue(js.body().contains("EventSource"), "SSE 逻辑在 app.js");
    }

    @Test
    void rejectsUnsafeOrMissingStaticAssets() throws Exception {
        // /web/ 白名单：单段已知后缀文件名，多段路径与穿越一律 404
        Session session = Session.create(tempDir.resolve("sessions"));
        start(session, scriptedAgent(session, "ok"));

        assertEquals(404, fetch("/web/missing.css").statusCode(), "未知资源 404");
        assertEquals(404, fetch("/web/../secret.txt").statusCode(), "路径穿越拒绝");
        assertEquals(404, fetch("/web/sub/x.css").statusCode(), "多段路径拒绝");
    }

    @Test
    void statusJsonContainsSnapshotsAndTools() throws Exception {
        Session session = Session.create(tempDir.resolve("sessions"));
        start(session, scriptedAgent(session, "ok"));
        String body = get("/api/status");
        JsonNode json = new ObjectMapper().readTree(body);
        assertEquals("ACTIVE", json.path("plugins").get(0).path("state").asText(), "ToolsPlugin ACTIVE");
        assertTrue(json.path("tools").toString().contains("echo"), "工具清单含 echo");
    }

    @Test
    void messagePostRunsAgentAndEventsLandInSession() throws Exception {
        Session session = Session.create(tempDir.resolve("sessions"));
        start(session, scriptedAgent(session, "你好呀"));

        HttpResponse<String> ack = post("/api/message", "{\"text\": \"你好\"}");
        assertEquals(202, ack.statusCode(), "POST 立即 202（异步执行）");
        for (int i = 0; i < 50 && session.events().size() < 2; i++) {
            Thread.sleep(100);
        }
        assertEquals(2, session.events().size(), "user/message + assistant/chunk");
        assertEquals(SessionEvent.USER_MESSAGE, session.events().get(0).type());
        assertEquals("你好", session.events().get(0).text());
        assertEquals("你好呀", session.events().get(1).text());
    }

    @Test
    void concurrentMessageRejectedWith409() throws Exception {
        ChatAgent slow = (userText, listener) -> {
            listener.onChunk("慢回复");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AgentReply("慢回复", List.of(), true);
        };
        start(Session.create(tempDir.resolve("sessions")), slow);

        HttpResponse<String> first = post("/api/message", "{\"text\": \"第一条\"}");
        assertEquals(202, first.statusCode());
        Thread.sleep(100);
        HttpResponse<String> second = post("/api/message", "{\"text\": \"第二条\"}");
        assertEquals(409, second.statusCode(), "执行中再发 → 409（单入口串行）");
    }

    @Test
    void sessionNewCreatesFreshSession() throws Exception {
        Session first = Session.create(tempDir.resolve("sessions"));
        start(first, scriptedAgent(first, "ok"));

        HttpResponse<String> response = post("/api/session/new", "{}");
        assertEquals(200, response.statusCode());
        JsonNode json = new ObjectMapper().readTree(response.body());
        assertTrue(json.has("id"), "返回新会话 id");
        assertTrue(!json.get("id").asText().equals(first.id()), "id 不同于旧会话");
        assertEquals(1, changedSessions.size(), "会话变更回调触发（agent 重建信号）");
        assertEquals(json.get("id").asText(), changedSessions.get(0).id(), "回调拿到已换绑的新会话");
    }

    @Test
    void sessionSwitchRebindsAndNotifiesRebuild() throws Exception {
        // 分脑防御（BUG-20260914-02）：switch 换绑后必须触发会话变更回调重建 agent
        Session other = Session.create(tempDir.resolve("web-sessions"));
        other.append(SessionEvent.userMessage("另一个会话的历史"));
        Session current = Session.create(tempDir.resolve("sessions"));
        start(current, scriptedAgent(current, "ok"));

        HttpResponse<String> response = post("/api/session/switch",
                "{\"id\": \"" + other.id() + "\"}");
        assertEquals(200, response.statusCode());
        assertEquals(other.id(), face.currentSession().id(), "当前会话已换绑");
        assertTrue(changedSessions.stream().anyMatch(s -> s.id().equals(other.id())),
                "会话变更回调以换绑会话触发（agent 重建信号）");
    }

    /** 单段直答 mock agent（模拟真实 ToolCallingAgent：user 消息入会话 + chunk 交 listener）。 */
    private ChatAgent scriptedAgent(Session session, String reply) {
        return (userText, listener) -> {
            session.append(SessionEvent.userMessage(userText));
            listener.onChunk(reply);
            return new AgentReply(reply, List.of(), true);
        };
    }
}
