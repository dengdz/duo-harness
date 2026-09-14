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
        return start(session, agent, null);
    }

    /** 装配重载：注入上下文治理（状态面占用查询的同源数据源；null = 无治理）。 */
    private WebFace start(Session session, ChatAgent agent,
                          dev.duo.harness.agent.ContextGovernance governance) throws IOException {
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
        face = WebFace.start(0, ctx, tools, session, agent, governance, null,
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
    void servesVendoredMarkdownLibraries() throws Exception {
        // Markdown 渲染依赖（工单 M10-08）：vendor 单文件入库，运行时零外联；消毒库与渲染库同在
        Session session = Session.create(tempDir.resolve("sessions"));
        start(session, scriptedAgent(session, "ok"));

        HttpResponse<String> marked = fetch("/web/marked.min.js");
        assertEquals(200, marked.statusCode(), "marked.min.js 可达");
        assertTrue(marked.body().contains("marked v"), "渲染库内容在场");

        HttpResponse<String> purify = fetch("/web/purify.min.js");
        assertEquals(200, purify.statusCode(), "purify.min.js 可达");
        assertTrue(purify.body().contains("DOMPurify"), "消毒库内容在场");
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
    void statusCarriesContextOccupancyWhenGovernancePresent() throws Exception {
        // 工单 M10-07：/api/status 暴露上下文占用，与治理计量同源同口径
        Session session = Session.create(tempDir.resolve("sessions"));
        session.append(SessionEvent.userMessage("问"));
        session.append(SessionEvent.assistantMessage("答",
                new dev.duo.harness.session.TokenUsage(1200, 340, 1540)));
        dev.duo.harness.agent.ContextGovernance governance =
                new dev.duo.harness.agent.ContextGovernance(new dev.duo.harness.llm.LlmAdapter() {
                    @Override
                    public void stream(dev.duo.harness.llm.ChatRequest request,
                                       java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                        throw new UnsupportedOperationException("占用查询不触 LLM");
                    }

                    @Override
                    public dev.duo.harness.llm.LlmTurn streamTurn(dev.duo.harness.llm.ChatRequest request,
                                                                  java.util.function.Consumer<String> textSink) {
                        throw new UnsupportedOperationException("占用查询不触 LLM");
                    }
                });
        start(session, scriptedAgent(session, "ok"), governance);

        JsonNode json = new ObjectMapper().readTree(get("/api/status"));
        assertEquals(1540, json.path("context").path("tokens").asLong(), "实测口径 prompt+completion");
        assertTrue(json.path("context").path("fromProvider").asBoolean(), "实测标记");
        assertEquals(dev.duo.harness.agent.ContextGovernance.CONTEXT_WINDOW_TOKENS,
                json.path("context").path("windowTokens").asLong(), "窗口常量随行");
        assertTrue(json.path("context").path("thresholdTokens").asLong() > 0, "阈值随行（前端阈值对照用）");
    }

    @Test
    void statusOmitsContextWhenNoGovernance() throws Exception {
        Session session = Session.create(tempDir.resolve("sessions"));
        start(session, scriptedAgent(session, "ok"));

        JsonNode json = new ObjectMapper().readTree(get("/api/status"));
        assertTrue(json.path("context").isMissingNode(), "无治理装配时 context 字段缺席（向后兼容）");
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

    @Test
    void sessionSwitchRejectsInvalidSessionIds() throws Exception {
        // 白名单校验（工单 M10-02）：id 只接受生成形态（日期时间 + 4 位十六进制），
        // 路径分隔符、穿越串、任意杂串一律 400，不进路径解析
        Session current = Session.create(tempDir.resolve("sessions"));
        start(current, scriptedAgent(current, "ok"));

        for (String bad : List.of("../escape", "a/b", "abc", "..", "1/../../etc")) {
            HttpResponse<String> response = post("/api/session/switch",
                    "{\"id\": \"" + bad + "\"}");
            assertEquals(400, response.statusCode(), "非法 id 应拒绝: " + bad);
        }
    }

    @Test
    void sessionSwitchUnknownIdFailsWithoutEchoingInternals() throws Exception {
        // 合法格式但不存在：404 + 通用描述——异常细节（含文件系统路径）不回显
        Session current = Session.create(tempDir.resolve("sessions"));
        start(current, scriptedAgent(current, "ok"));

        HttpResponse<String> response = post("/api/session/switch",
                "{\"id\": \"20260101-000000-beef\"}");
        assertEquals(404, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("切换失败"), body);
        assertTrue(!body.contains("Exception") && !body.contains(tempDir.toString()),
                "错误响应不得回显异常类型或内部路径: " + body);
    }

    @Test
    void oversizedRequestBodyRejectedWith413() throws Exception {
        // 请求体上限（工单 M10-02）：超限拒收，防误粘贴/恶意超大 body 占内存
        Session current = Session.create(tempDir.resolve("sessions"));
        start(current, scriptedAgent(current, "ok"));

        String big = "x".repeat(1_000_001);
        HttpResponse<String> response = post("/api/message", "{\"text\": \"" + big + "\"}");
        assertEquals(413, response.statusCode(), "超过 1MB 上限应 413");
    }

    @Test
    void sessionsJsonListsSessionsWithCurrentMark() throws Exception {
        // /api/sessions 端点形态（M8 零覆盖补齐）：列表、修改时间字段、current 标记
        Path listed = tempDir.resolve("web-sessions");
        Session first = Session.create(listed);
        first.append(SessionEvent.userMessage("历史"));
        Session current = Session.create(listed);
        start(current, scriptedAgent(current, "ok"));
        face.onNewSession(() -> Session.create(listed));

        JsonNode json = new ObjectMapper().readTree(get("/api/sessions"));
        assertTrue(json.path("sessions").isArray() && json.path("sessions").size() >= 1, "返回会话数组");
        JsonNode currentNode = null;
        for (JsonNode node : json.path("sessions")) {
            assertTrue(node.has("id") && node.has("lastModifiedMs") && node.has("current"), "字段齐备");
            if (node.path("current").asBoolean()) {
                currentNode = node;
            }
        }
        assertTrue(currentNode != null, "恰有 current 标记");
        assertEquals(current.id(), currentNode.path("id").asText(), "current 指向当前会话");
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
