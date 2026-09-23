package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标签级会话绑定用例（M24 工单 07）：tabId 上报（X-Tab-Id 头 / SSE ?tabId= 查询串）→
 * 服务端每标签一份会话绑定（未知 tabId 懒创建 = 新标签默认新建会话）；双标签会话事件
 * 互不串流（审批卡路由的基础）；/switch、/new 只换绑发起标签；busy 标签拒换绑；
 * fail-closed 按标签选择性拒绝（A 离场不牵连 B）。
 */
class WebFaceTabTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebFaceTabTest —— 标签级会话绑定：新标签懒建会话、双标签事件隔离、"
                + "切换绑定发起标签、busy 换绑守卫、审批卡按标签路由、fail-closed 按标签（7 用例） ===");
    }

    interface ToolsView {

        ToolsService tools();
    }

    interface AnswersView {

        dev.duo.harness.tools.InteractionService answers();
    }

    @TempDir
    Path tempDir;

    private WebFace face;
    private Context faceCtx;
    private final HttpClient client = HttpClient.newHttpClient();
    /** 会话变更回调的记录（调试观测用）。 */
    private final List<Session> changedSessions = new CopyOnWriteArrayList<>();
    /** 标签会话的 agent 工厂（测试按创建序注入行为；null = 缺省回声脚本）。 */
    private volatile Function<Session, ChatAgent> tabAgentFactory;

    @AfterEach
    void tearDown() {
        if (face != null) {
            face.stop();
        }
    }

    /** 装配：初始会话归匿名上下文；标签上下文按工厂（或回声脚本）建 agent。 */
    private WebFace start(Session session, ChatAgent agent, WebAnswerer webAnswerer) throws IOException {
        Context ctx = Context.root();
        faceCtx = ctx;
        if (webAnswerer != null) {
            ctx.plugin(new dev.duo.harness.tools.InteractionPlugin(), null).awaitStartup();
        }
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
        face = WebFace.start(0, ctx, tools, session, agent, null, webAnswerer,
                sessionsDir());
        if (webAnswerer != null) {
            // 生产同款接线：审计装饰器委托 web answerer 并落 approval/* 事件
            ctx.as(AnswersView.class).answers().register(ctx,
                    new dev.duo.harness.agent.AuditingAnswerer(face::currentSession, webAnswerer));
        }
        face.onNewSession(() -> Session.create(sessionsDir()));
        face.onSessionChanged(changed -> {
            changedSessions.add(changed);
            Function<Session, ChatAgent> factory = tabAgentFactory;
            return factory != null ? factory.apply(changed) : scriptedAgent(changed, "ok");
        });
        return face;
    }

    private Path sessionsDir() {
        return tempDir.resolve("sessions");
    }

    /** 回声脚本 agent（生产回调同构：绑定传入会话——换绑后事件落新会话）。 */
    private ChatAgent scriptedAgent(Session session, String reply) {
        return (userText, listener) -> {
            session.append(SessionEvent.userMessage(userText));
            listener.onChunk(reply);
            return new AgentReply(reply, List.of(), true);
        };
    }

    private HttpResponse<String> post(String path, String jsonBody, String tabId)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + face.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (tabId != null) {
            builder.header("X-Tab-Id", tabId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String getSessions(String tabId) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + face.port() + "/api/sessions")).GET();
        if (tabId != null) {
            builder.header("X-Tab-Id", tabId);
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    /** 会话列表 JSON 里 current=true 的会话 id（各标签各看各的"当前"）。 */
    private static String currentIdOf(String sessionsJson) throws Exception {
        JsonNode root = new ObjectMapper().readTree(sessionsJson);
        for (JsonNode node : root.path("sessions")) {
            if (node.path("current").asBoolean(false)) {
                return node.path("id").asText();
            }
        }
        return null;
    }

    private SseCollector openSse(String tabId) throws Exception {
        String url = "http://127.0.0.1:" + face.port() + "/api/events";
        if (tabId != null) {
            url += "?tabId=" + tabId; // SSE 通道（EventSource 无自定义头），同前端 ?tabId=
        }
        return new SseCollector(client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()).body());
    }

    private static final class SseCollector implements AutoCloseable {
        private final java.io.InputStream body;
        private final Thread reader;
        private final AtomicReference<String> captured = new AtomicReference<>("");

        private SseCollector(java.io.InputStream body) {
            this.body = body;
            this.reader = Thread.ofVirtual().start(this::pump);
        }

        private void pump() {
            byte[] buffer = new byte[1_024];
            StringBuilder collected = new StringBuilder();
            try {
                int read;
                while ((read = body.read(buffer)) != -1) {
                    collected.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    captured.set(collected.toString());
                }
            } catch (Exception e) {
                // 关流打断：已累积部分保留
            }
        }

        String awaitText(long millis) throws InterruptedException {
            Thread.sleep(millis);
            return captured.get();
        }

        @Override
        public void close() {
            try {
                body.close();
            } catch (Exception ignored) {
                // 已关
            }
            reader.interrupt();
        }
    }

    @Test
    void unknownTabIdLazilyCreatesOwnSessionDefaultUntouched() throws Exception {
        // 新标签默认新建会话（互踩隔离优先）：首个携 tabId 的请求懒建标签上下文，
        // 初始会话只归匿名上下文（无 tabId 请求）——两条线互不可见对方的"当前"
        Session initial = Session.create(sessionsDir());
        start(initial, scriptedAgent(initial, "ok"), null);

        String tabAView = getSessions("tabA");
        String currentA = currentIdOf(tabAView);
        assertNotNull(currentA, "新标签懒建会话后必有当前项");
        assertNotEquals(initial.id(), currentA, "新标签拿新会话，不接续初始会话");

        String anonymousView = getSessions(null);
        assertEquals(initial.id(), currentIdOf(anonymousView),
                "无 tabId 请求（匿名上下文）的当前仍是初始会话");

        // 同一 tabId 二次到达复用同一上下文（不重复建会话）：当前项稳定
        assertEquals(currentA, currentIdOf(getSessions("tabA")), "同 tabId 复用同一会话");
    }

    @Test
    void malformedTabIdRejectedNotMergedIntoAnonymous() throws Exception {
        // 坏 tabId 静默并入匿名上下文会让互踩复活（fail-closed）：400 拒绝（头与 SSE 查询串双通道同规）
        Session initial = Session.create(sessionsDir());
        start(initial, scriptedAgent(initial, "ok"), null);
        HttpResponse<String> bad = post("/api/message", "{\"text\":\"hi\"}", "../evil");
        assertEquals(400, bad.statusCode(), "非法 tabId（路径形态字符）拒绝");
        HttpResponse<java.io.InputStream> badSse = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + face.port()
                        + "/api/events?tabId=../evil")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(400, badSse.statusCode(), "SSE 查询串非法 tabId 同样拒绝");
        badSse.body().close();
    }

    @Test
    void twoTabsBindDistinctSessionsAndEventsStayIsolated() throws Exception {
        // 双标签并行：A 的对话事件只推 A 的 SSE，B 的 SSE 收不到——互踩隔离与
        // 审批卡不串标签的数据通道基础
        Session initial = Session.create(sessionsDir());
        start(initial, scriptedAgent(initial, "ok"), null);

        try (SseCollector sseA = openSse("tabA"); SseCollector sseB = openSse("tabB")) {
            sseA.awaitText(400); // 回放落地（各自会话的尾部快照）
            sseB.awaitText(400);
            HttpResponse<String> ack = post("/api/message", "{\"text\":\"A 的消息\"}", "tabA");
            assertEquals(202, ack.statusCode());
            String streamA = sseA.awaitText(1_000);
            assertTrue(streamA.contains("A 的消息"), "A 标签收到自己会话的事件: " + streamA);
            assertTrue(streamA.contains("\"type\":\"user/message\""), "user/message 在场");
            String streamB = sseB.awaitText(300);
            assertFalse(streamB.contains("A 的消息"), "B 标签不得收到 A 会话的事件: " + streamB);
        }
    }

    @Test
    void switchBindsOnlyInitiatingTab() throws Exception {
        // resume 走面板入口绑定发起标签：A 切换后 A 的"当前"变，B 与匿名上下文不动
        Session initial = Session.create(sessionsDir());
        Session other = Session.create(sessionsDir());
        other.append(SessionEvent.userMessage("历史会话"));
        other.close(); // 释放测试进程持有的独占锁（留文件供切换加载）
        start(initial, scriptedAgent(initial, "ok"), null);

        getSessions("tabA"); // 确保两标签上下文已建
        getSessions("tabB");
        HttpResponse<String> res = post("/api/session/switch", "{\"id\":\"" + other.id() + "\"}", "tabA");
        assertEquals(200, res.statusCode(), "切换成功: " + res.body());

        assertEquals(other.id(), currentIdOf(getSessions("tabA")), "发起标签 A 已换绑");
        assertNotEquals(other.id(), currentIdOf(getSessions("tabB")), "B 标签绑定不动");
        assertEquals(initial.id(), currentIdOf(getSessions(null)), "匿名上下文绑定不动");
    }

    @Test
    void busyTabRefusesNewAndSwitchWhileOtherTabProceeds() throws Exception {
        // busy 守卫（换绑会关闭正在写的会话——换绑与重建 agent 是同一动作两面）：
        // A 执行中拒 /new 与 /switch；B 标签不受牵连照常开新话题
        Session initial = Session.create(sessionsDir());
        Session other = Session.create(sessionsDir());
        CountDownLatch gate = new CountDownLatch(1);
        ChatAgent gated = (userText, listener) -> {
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AgentReply("ok", List.of(), true);
        };
        tabAgentFactory = changed -> gated; // A、B 标签的会话都挂闸门 agent（只有 A 真正开 turn）
        start(initial, scriptedAgent(initial, "ok"), null);

        assertEquals(202, post("/api/message", "{\"text\":\"长任务\"}", "tabA").statusCode(), "A 标签受理开 turn");
        assertEquals(409, post("/api/session/new", "{}", "tabA").statusCode(),
                "busy 标签拒 /new");
        assertEquals(409, post("/api/session/switch", "{\"id\":\"" + other.id() + "\"}", "tabA")
                .statusCode(), "busy 标签拒 /switch");
        assertEquals(200, post("/api/session/new", "{}", "tabB").statusCode(),
                "B 标签不受 A 的 busy 牵连");
        gate.countDown();
    }

    @Test
    void approvalCardRoutedToOwningTabOnly() throws Exception {
        // 审批卡按 tabId 路由：turn 内的审批（经审计桥落 turn 会话）只推发起标签——
        // A 标签的审批不弹到 B 标签；按卡片 id 精确回填闭环
        Session initial = Session.create(sessionsDir());
        WebAnswerer webAnswerer = new WebAnswerer(60_000);
        start(initial, scriptedAgent(initial, "ok"), webAnswerer);
        dev.duo.harness.tools.InteractionService answers = faceCtx.as(AnswersView.class).answers();
        AtomicReference<dev.duo.harness.tools.InteractionAnswer> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        tabAgentFactory = changed -> (userText, listener) -> {
            got.set(answers.ask(dev.duo.harness.tools.InteractionRequest.approval("write_file", "{}")));
            done.countDown();
            return new AgentReply("ok", List.of(), true);
        };

        try (SseCollector sseA = openSse("tabA"); SseCollector sseB = openSse("tabB")) {
            sseA.awaitText(400);
            sseB.awaitText(400);
            assertEquals(202, post("/api/message", "{\"text\":\"要写文件\"}", "tabA").statusCode(), "A 标签受理");
            String streamA = sseA.awaitText(1_500);
            assertTrue(streamA.contains("approval/requested"),
                    "A 标签收到审批卡事件: " + streamA);
            assertFalse(sseB.awaitText(300).contains("approval/requested"),
                    "B 标签不得收到 A 的审批卡");

            // 按卡片 id 精确回填（卡片 id 借 toolCallId 通道）
            String cardId = extractCardId(streamA);
            assertNotNull(cardId, "审批事件携带卡片 id");
            HttpResponse<String> answer = post("/api/answer",
                    "{\"id\":\"" + cardId + "\",\"decision\":\"approve\"}", "tabA");
            assertEquals(200, answer.statusCode(), "按卡片 id 回填受理");
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertTrue(got.get().approved(), "回填完成 A 的挂起审批");
        }
    }

    /** 从 SSE 流文本提取 approval/requested 帧的卡片 id（UUID；detail 含 {} 不能用 [^}] 截取）。 */
    private static String extractCardId(String stream) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"toolCallId\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"")
                .matcher(stream);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void failClosedPerTabOnlyAffectsDisconnectedTab() throws Exception {
        // fail-closed 按标签：A 标签连接全离场且宽限期过 → 只拒 A 的挂起审批；
        // B 标签的挂起审批不受牵连仍可作答（"是否仍有人能看见"以标签为界）
        Session initial = Session.create(sessionsDir());
        WebAnswerer webAnswerer = new WebAnswerer(60_000);
        start(initial, scriptedAgent(initial, "ok"), webAnswerer);
        dev.duo.harness.tools.InteractionService answers = faceCtx.as(AnswersView.class).answers();
        List<AtomicReference<dev.duo.harness.tools.InteractionAnswer>> got = new CopyOnWriteArrayList<>();
        List<CountDownLatch> dones = new CopyOnWriteArrayList<>();
        tabAgentFactory = changed -> {
            AtomicReference<dev.duo.harness.tools.InteractionAnswer> ref = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            got.add(ref);
            dones.add(latch);
            return (userText, listener) -> {
                ref.set(answers.ask(dev.duo.harness.tools.InteractionRequest.approval("write_file", "{}")));
                latch.countDown();
                return new AgentReply("ok", List.of(), true);
            };
        };

        assertEquals(202, post("/api/message", "{\"text\":\"A 请求\"}", "tabA").statusCode(), "A 标签受理");
        assertEquals(202, post("/api/message", "{\"text\":\"B 请求\"}", "tabB").statusCode(), "B 标签受理");
        long deadline = System.currentTimeMillis() + 5_000;
        while (webAnswerer.pendingCount() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(webAnswerer.pendingCount() >= 2, "两个标签的审批挂起均已就位");

        // 摘除 A 标签的连接（测试用离场注入：列表本无 A 连接，判定只看该标签无连接）
        face.removeClient(new WebFace.SseClient(new java.io.ByteArrayOutputStream(), "tabA"));
        Thread.sleep(500);
        assertTrue(webAnswerer.currentPending() != null, "宽限期内不得立即拒绝");
        assertTrue(dones.get(0).await(WebFace.FAIL_CLOSED_GRACE_MS + 1_500, TimeUnit.MILLISECONDS),
                "宽限到期后 A 的挂起审批收口");
        assertEquals(dev.duo.harness.tools.InteractionAnswer.SOURCE_FAIL_CLOSED, got.get(0).get().source(),
                "A 离场 → A 的审批 fail-closed");

        assertTrue(webAnswerer.currentPending() != null, "B 的挂起审批仍在");
        assertTrue(webAnswerer.completeById(extractCardIdFromPending(webAnswerer), "approve", List.of()),
                "B 的审批仍可作答（不被 A 牵连）");
        assertTrue(dones.get(1).await(2, TimeUnit.SECONDS));
        assertTrue(got.get(1).get().approved());
    }

    /** 取当前挂起项的卡片 id（经 currentPending 观测；测试辅助）。 */
    private static String extractCardIdFromPending(WebAnswerer webAnswerer) {
        WebAnswerer.Pending pending = webAnswerer.currentPending();
        return pending == null ? null : pending.id();
    }
}
