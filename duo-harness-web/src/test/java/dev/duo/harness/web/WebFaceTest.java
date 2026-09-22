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
import java.nio.channels.FileChannel;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web 双面骨架用例（HttpServer 先例）：起停与 loopback 绑定、静态单页、状态面
 * JSON、SSE 流（存量回放 + 实时推送）、对话面（POST /api/message → agent 执行 →
 * 事件入会话经 SSE 呈现）、/new 端点。
 */
class WebFaceTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebFaceTest —— Web 面：静态资源（拆分件/vendor 库/白名单 404）、"
                + "状态 JSON（含上下文占用）、SSE 回放（尾部快照头帧/边界起点/增量游标/越界兜底/帧序号）、"
                + "历史分页端点（窗口/翻转/边界拒绝/连续性）、"
                + "占用标注（occupied 字段）、安全（id 白名单/请求体上限/错误脱敏/入口栅栏 Host 与 Origin）、会话锁冲突与幂等切换、fail-closed 宽限、"
                + "子任务回放端点（含标题字段）、回答端点结构化协议（decision 审批两态/自由文本不误判/非法体 400 无兼容层）、后台完成通知双路径、后台任务可见化（41 用例） ===");
    }

    interface ToolsView {

        ToolsService tools();
    }

    @TempDir
    Path tempDir;

    private WebFace face;
    /** helper 装配的根上下文（HITL 用例取 answers 服务用）。 */
    private Context faceCtx;
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
        return start(session, agent, null, null);
    }

    /** 装配重载：注入上下文治理（状态面占用查询的同源数据源；null = 无治理）。 */
    private WebFace start(Session session, ChatAgent agent,
                          dev.duo.harness.agent.governance.ContextGovernance governance) throws IOException {
        return start(session, agent, governance, null);
    }

    /**
     * 装配全参重载：webAnswerer 非空时装配交互 seam 最小集（InteractionPlugin +
     * web answerer + 审计桥）——HITL 语义用例的供给。
     */
    private WebFace start(Session session, ChatAgent agent,
                          dev.duo.harness.agent.governance.ContextGovernance governance, WebAnswerer webAnswerer) throws IOException {
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
        face = WebFace.start(0, ctx, tools, session, agent, governance, webAnswerer,
                tempDir.resolve("web-sessions"));
        if (webAnswerer != null) {
            // 生产同款接线（WebPlugin）：只注册审计装饰器——它委托 web answerer 作答并
            // 落 approval/requested、approval/decided 审计事件（回答者链首个非空胜出，
            // 直注册 webAnswerer 会让装饰器永不执行、审批卡事件缺失）
            ctx.as(AnswersView.class).answers().register(ctx,
                    new dev.duo.harness.agent.AuditingAnswerer(face::currentSession, webAnswerer));
        }
        // 会话变更接线：/new 与 /switch 换绑后回调（装配层职责，骨架用例记录变更）
        face.onNewSession(() -> Session.create(tempDir.resolve("web-sessions")));
        face.onSessionChanged(changed -> changedSessions.add(changed));
        return face;
    }

    /** commands 服务的视图接口（方法名即服务名 "commands"）。 */
    interface CommandsView {

        dev.duo.harness.agent.commands.CommandsRegistry commands();
    }

    /** answers 服务的视图接口（方法名即服务名 "answers"）。 */
    interface AnswersView {

        dev.duo.harness.tools.InteractionService answers();
    }

    private HttpResponse<String> post(String path, String jsonBody) throws IOException, InterruptedException {
        return post(path, jsonBody, null);
    }

    /** 带显式 Origin 头的 POST（栅栏用例伪造跨站/同源 Origin；null = 不带）。 */
    private HttpResponse<String> post(String path, String jsonBody, String origin) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + face.port() + path))
                .header("Content-Type", "application/json");
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = fetch(path);
        assertEquals(200, response.statusCode(), path);
        return response.body();
    }

    private HttpResponse<String> fetch(String path) throws IOException, InterruptedException {
        return fetch(path, null);
    }

    private HttpResponse<String> fetch(String path, String origin) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + face.port() + path)).GET();
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
        dev.duo.harness.agent.governance.ContextGovernance governance =
                new dev.duo.harness.agent.governance.ContextGovernance(new dev.duo.harness.llm.LlmAdapter() {
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
        assertEquals(dev.duo.harness.agent.governance.ContextGovernance.CONTEXT_WINDOW_TOKENS,
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
    void stopEndpointInterruptsBusyAgentAndFreesForNextMessage() throws Exception {
        // /api/stop（M23 工单 02，ADR-0025 决策一）：busy 中 202 受理并打断 agent（协作式）；
        // 中断收口后 busy 解除——下一条消息正常受理（可恢复态）；空闲时 409
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> sender =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interrupted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        ChatAgent stub = new ChatAgent() {
            @Override
            public AgentReply send(String userText, dev.duo.harness.agent.AgentListener listener) {
                sender.set(Thread.currentThread());
                listener.onChunk("部分输出");
                started.countDown();
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
                return interrupted.get()
                        ? new AgentReply("已中断（协作式暂停）", List.of(), false, true)
                        : new AgentReply("完整回答", List.of(), true);
            }

            @Override
            public boolean requestInterrupt() {
                Thread active = sender.get();
                if (active == null) {
                    return false;
                }
                interrupted.set(true);
                active.interrupt();
                return true;
            }
        };
        start(Session.create(tempDir.resolve("stop-sessions")), stub);

        HttpResponse<String> first = post("/api/message", "{\"text\": \"长任务\"}");
        assertEquals(202, first.statusCode());
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS), "send 应已开始");
        HttpResponse<String> stop = post("/api/stop", "{}");
        assertEquals(202, stop.statusCode(), "busy 中停止请求受理");
        assertTrue(stop.body().contains("interrupt-requested"), "结构化受理: " + stop.body());

        // 中断收口（打断即醒，5s sleep 不等满）→ busy 解除 → 下一条消息正常受理
        long deadline = System.currentTimeMillis() + 5_000;
        HttpResponse<String> next = null;
        while (System.currentTimeMillis() < deadline) {
            next = post("/api/message", "{\"text\": \"续接\"}");
            if (next.statusCode() == 202 && !next.body().contains("injected")) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals(202, next.statusCode(), "中断后下一条消息正常受理（可恢复态）");
        assertTrue(next.body().isEmpty(), "非注入受理（busy 已解除）: " + next.body());
    }

    @Test
    void stopEndpointRefusesBeforeAnyMessage() throws Exception {
        // 空闲 409：从未发过消息时停止按钮的请求得到明确拒绝（按钮侧据此复位）
        start(Session.create(tempDir.resolve("idle-sessions")), (userText, listener) ->
                new AgentReply("答", List.of(), true));
        HttpResponse<String> idleStop = post("/api/stop", "{}");
        assertEquals(409, idleStop.statusCode());
        assertTrue(idleStop.body().contains("无执行中任务"), idleStop.body());
    }


    @Test
    void backgroundTaskCompletionRoutesIdleNewTurnAndBusyNextTurn() throws Exception {
        // 后台完成通知路由（M23 工单 04）：空闲期任务完成 → 通知作为新 turn 送 agent
        // （桩记录 send 文本）；busy 期任务完成 → injectNextTurn 挂收件箱
        dev.duo.harness.tools.fs.BackgroundTaskRegistry registry =
                new dev.duo.harness.tools.fs.BackgroundTaskRegistry();
        java.util.List<String> sent = new CopyOnWriteArrayList<>();
        java.util.List<String> queued = new CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch busyInFlight = new java.util.concurrent.CountDownLatch(1);
        ChatAgent stub = new ChatAgent() {
            @Override
            public AgentReply send(String userText, dev.duo.harness.agent.AgentListener listener) {
                sent.add(userText);
                if (userText.startsWith("[后台任务完成]")) {
                    return new AgentReply("已收到通知", List.of(), true);
                }
                try {
                    busyInFlight.countDown();
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new AgentReply("忙完了", List.of(), true);
            }

            @Override
            public boolean injectNextTurn(String text) {
                queued.add(text);
                return true;
            }
        };
        start(Session.create(tempDir.resolve("bg-sessions")), stub);
        face.setBackgroundTaskRegistry(registry);

        // 空闲路径：任务完成 → 通知自动开轮（桩 send 收到通知文本）
        registry.start(new ProcessBuilder("bash", "-c", "true").start(), "echo idle-notice");
        long deadline = System.currentTimeMillis() + 5_000;
        while (sent.stream().noneMatch(t -> t.startsWith("[后台任务完成]"))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(sent.stream().anyMatch(t -> t.startsWith("[后台任务完成]")),
                "空闲通知自动开轮: " + sent);

        // busy 路径：send 在飞（latch 拉住）→ 任务完成 → injectNextTurn 挂队
        HttpResponse<String> msg = post("/api/message", "{\"text\": \"忙任务\"}");
        assertEquals(202, msg.statusCode());
        assertTrue(busyInFlight.await(3, java.util.concurrent.TimeUnit.SECONDS));
        registry.start(new ProcessBuilder("bash", "-c", "true").start(), "echo busy-notice");
        deadline = System.currentTimeMillis() + 5_000;
        while (queued.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, queued.size(), "busy 通知挂 next-turn");
        assertTrue(queued.get(0).contains("busy-notice"), "通知文本完整: " + queued);
    }


    @Test
    void statusJsonCarriesBackgroundTasksAndConvergesAfterCompletion() throws Exception {
        // 后台任务可见化（M23 工单 06）：注册表在场时 /api/status 携带任务数组
        // （id/命令/状态/退出码）；任务完成/被 stop 后状态收敛（无幽灵 RUNNING）
        var registry = new dev.duo.harness.tools.fs.BackgroundTaskRegistry();
        ChatAgent stub = (userText, listener) -> new AgentReply("答", List.of(), true);
        start(Session.create(tempDir.resolve("bg-status")), stub);
        face.setBackgroundTaskRegistry(registry);

        // 无任务：无字段或空数组（前端占位 —）
        String empty = get("/api/status");
        assertFalse(empty.contains("bg-"), "无任务不出现任务条目: "
                + empty.substring(0, Math.min(120, empty.length())));

        // 运行中：RUNNING 状态可见
        var task = registry.start(new ProcessBuilder("bash", "-c", "sleep 5").start(), "sleep 5");
        String running = get("/api/status");
        assertTrue(running.contains("\"taskId\":\"" + task.taskId() + "\""),
                "运行中任务可见: " + running);
        assertTrue(running.contains("RUNNING"), "运行中状态: " + running);

        // 终态收敛：task-stop 后状态转 KILLED（无幽灵 RUNNING）
        new dev.duo.harness.tools.fs.TaskStopTool(registry).execute(
                new ToolExecution("task-stop",
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                .objectNode().put("taskId", task.taskId())));
        long deadline = System.currentTimeMillis() + 5_000;
        String done = "";
        boolean killed = false;
        while (System.currentTimeMillis() < deadline) {
            done = get("/api/status");
            if (done.contains("KILLED")) { killed = true; break; }
            Thread.sleep(20);
        }
        assertTrue(killed, "终态收敛（无幽灵 RUNNING）: " + done);
    }

    @Test
    void statusJsonFiltersTasksByWebOwnership() throws Exception {
        // 归属过滤（M23 工单 06 验收修正）：状态面只列 web 发起（或无归属）的任务——
        // CLI 侧任务不串显到浏览器新会话
        var registry = new dev.duo.harness.tools.fs.BackgroundTaskRegistry();
        ChatAgent stub = (userText, listener) -> new AgentReply("答", List.of(), true);
        start(Session.create(tempDir.resolve("bg-owner")), stub);
        face.setBackgroundTaskRegistry(registry);

        var webTask = registry.start(new ProcessBuilder("bash", "-c", "sleep 30").start(),
                "sleep 30", dev.duo.harness.agent.ChatAgent.PRESENTER_WEB);
        var cliTask = registry.start(new ProcessBuilder("bash", "-c", "sleep 30").start(),
                "sleep 30", dev.duo.harness.agent.ChatAgent.PRESENTER_CLI);
        String status = get("/api/status");
        assertTrue(status.contains("\"taskId\":\"" + webTask.taskId() + "\""),
                "web 任务可见: " + status);
        assertFalse(status.contains("\"taskId\":\"" + cliTask.taskId() + "\""),
                "cli 任务不串显: " + status);
        registry.shutdownAll();
    }

    @Test
    void completionNoticeIgnoresCliOwnedTasks() throws Exception {
        // 归属过滤（M23 工单 06 验收修正）：CLI 侧任务完成不在 Web 开通知轮/注入
        List<String> sent = new CopyOnWriteArrayList<>();
        ChatAgent stub = (userText, listener) -> {
            sent.add(userText);
            return new AgentReply("答", List.of(), true);
        };
        start(Session.create(tempDir.resolve("bg-owner-notice")), stub);
        var registry = new dev.duo.harness.tools.fs.BackgroundTaskRegistry();
        face.setBackgroundTaskRegistry(registry);

        registry.start(new ProcessBuilder("bash", "-c", "true").start(), "echo cli-side",
                dev.duo.harness.agent.ChatAgent.PRESENTER_CLI);
        Thread.sleep(500); // 任务早已终态：无归属过滤放行的通知即无轮开启
        assertTrue(sent.isEmpty(), "cli 任务完成不触发 Web 通知轮: " + sent);
    }

    @Test
    void concurrentMessageInjectedWhileBusy() throws Exception {
        // 运行中治理（M19 steer，ADR-0020 决策 8）：执行中 POST 不再 409——进 agent
        // 注入收件箱，202 + "已注入" 轻提示；注入文本由 agent 在迭代边界排干
        AtomicReference<String> injected = new AtomicReference<>();
        ChatAgent slow = new ChatAgent() {
            @Override
            public dev.duo.harness.agent.AgentReply send(String userText,
                                                         dev.duo.harness.agent.AgentListener listener) {
                listener.onChunk("慢回复");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new AgentReply("慢回复", List.of(), true);
            }

            @Override
            public boolean injectUserMessage(String text) {
                injected.set(text);
                return true;
            }
        };
        start(Session.create(tempDir.resolve("sessions")), slow);

        HttpResponse<String> first = post("/api/message", "{\"text\": \"第一条\"}");
        assertEquals(202, first.statusCode());
        Thread.sleep(100);
        HttpResponse<String> second = post("/api/message", "{\"text\": \"第二条\"}");
        assertEquals(202, second.statusCode(), "执行中再发 → 202（注入受理，非 409）");
        assertTrue(second.body().contains("已注入，待当前步骤完成"),
                "结构化受理随响应体返回: " + second.body());
        assertEquals("第二条", injected.get(), "文本已交 agent 注入收件箱");
    }

    @Test
    void concurrentMessageKeeps409WhenAgentCannotSteer() throws Exception {
        // 兜底：不支持注入的 agent（测试桩/旧实现）保留 409 语义——行为不静默漂移
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
        assertEquals(409, second.statusCode(), "不支持注入的 agent → 409（单入口串行保留）");
    }

    // ---- M19 工单 02：Web 斜杠入口（ADR-0020 决策 3/5） ----

    /** 斜杠用例夹具：基础树后挂 commands（+skills）服务并注册三条例子命令。 */
    private dev.duo.harness.agent.commands.CommandsRegistry mountCommands(boolean withSkills) {
        faceCtx.plugin(new dev.duo.harness.agent.commands.CommandsPlugin(),
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode())
                .awaitStartup();
        if (withSkills) {
            faceCtx.plugin(new dev.duo.harness.agent.prompt.PromptPlugin(),
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                            .put("systemPrompt", "测试")).awaitStartup();
            faceCtx.plugin(new dev.duo.harness.agent.skills.SkillsPlugin(),
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                            .putArray("disabled")).awaitStartup();
        }
        var commands = faceCtx.as(CommandsView.class).commands();
        var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        commands.register(faceCtx, new dev.duo.harness.agent.commands.CommandDefinition(
                "webping", "回声测试", dev.duo.harness.agent.commands.CommandScope.WEB, false,
                context -> "pong:" + context.args()));
        commands.register(faceCtx, new dev.duo.harness.agent.commands.CommandDefinition(
                "cliping", "仅终端", dev.duo.harness.agent.commands.CommandScope.CLI, false,
                context -> "cli-only"));
        commands.register(faceCtx, new dev.duo.harness.agent.commands.CommandDefinition(
                "safeview", "busySafe 查看", dev.duo.harness.agent.commands.CommandScope.ANY, true,
                context -> "safe-ok"));
        return commands;
    }

    @Test
    void slashCommandExecutesInWebWithoutTouchingAgent() throws Exception {
        // 斜杠前置命令解释：命中命令同步执行于 Web 进程内——run/done 审计落会话
        // （经 SSE 推送、前端渲染、刷新回放可见），agent 不被打扰、无 user/message
        Session session = Session.create(tempDir.resolve("sessions"));
        start(session, (userText, listener) -> {
            throw new AssertionError("斜杠命令不得触达 agent");
        });
        mountCommands(false);

        HttpResponse<String> res = post("/api/message", "{\"text\": \"/webping world\"}");
        assertEquals(202, res.statusCode());
        assertTrue(res.body().contains("\"outcome\":\"command\""),
                "命中执行的结构化受理: " + res.body());

        for (int i = 0; i < 50 && session.events().size() < 2; i++) {
            Thread.sleep(50);
        }
        assertEquals(2, session.events().size(), "command/run + command/done 恰两事件");
        var run = session.events().get(0);
        assertEquals(SessionEvent.COMMAND_RUN, run.type());
        assertEquals("webping", run.toolName());
        assertEquals("world", run.text());
        var done = session.events().get(1);
        assertEquals(SessionEvent.COMMAND_DONE, done.type());
        assertEquals("pong:world", done.text());
        assertTrue(session.events().stream().noneMatch(e ->
                        SessionEvent.USER_MESSAGE.equals(e.type())),
                "斜杠命令不 append user/message（不进模型历史）");
    }

    @Test
    void unknownCommandRefusedWithPresenterFilteredDigest() throws Exception {
        // 未知命令：报错附可用清单——命令按发起面（Web）过滤适用性，CLI 专属不列
        Session session = Session.create(tempDir.resolve("sessions"));
        start(session, (userText, listener) -> new AgentReply("答", List.of(), true));
        mountCommands(false);

        HttpResponse<String> res = post("/api/message", "{\"text\": \"/nope\"}");
        assertEquals(202, res.statusCode());
        assertTrue(res.body().contains("未知命令: /nope（可用命令: webping, safeview"),
                "清单按发起面过滤（cliping 为 CLI 专属不列）: " + res.body());
        assertTrue(session.events().isEmpty(), "拒绝类无审计事件");
    }

    @Test
    void cliOnlyCommandRefusedWithScopeHint() throws Exception {
        Session session = Session.create(tempDir.resolve("sessions"));
        start(session, (userText, listener) -> new AgentReply("答", List.of(), true));
        mountCommands(false);

        HttpResponse<String> res = post("/api/message", "{\"text\": \"/cliping\"}");
        assertTrue(res.body().contains("该命令仅在 CLI 可用"),
                "适用面不符提示: " + res.body());
        assertTrue(session.events().isEmpty());
    }

    @Test
    void busySafeCommandRunsWhileBusyAndUnsafeRefused() throws Exception {
        // busySafe 分级（HTTP 层面语义明确）：agent 执行中 busySafe 命令照常执行；
        // 非 busySafe 得到"执行中，需等待空闲"而非无差别 409
        Session session = Session.create(tempDir.resolve("sessions"));
        ChatAgent slow = (userText, listener) -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AgentReply("慢答", List.of(), true);
        };
        start(session, slow);
        mountCommands(false);

        HttpResponse<String> first = post("/api/message", "{\"text\": \"长任务\"}");
        assertEquals(202, first.statusCode());
        Thread.sleep(100);

        HttpResponse<String> safe = post("/api/message", "{\"text\": \"/safeview\"}");
        assertEquals(202, safe.statusCode());
        assertFalse(safe.body().contains("text"), "busySafe 命中无拒绝文案: " + safe.body());
        long deadline = System.currentTimeMillis() + 5_000;
        while (session.events().stream().noneMatch(e ->
                SessionEvent.COMMAND_DONE.equals(e.type()))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(session.events().stream().anyMatch(e ->
                        SessionEvent.COMMAND_DONE.equals(e.type()) && "safe-ok".equals(e.text())),
                "busySafe 命令执行中照常执行: " + session.events());

        HttpResponse<String> unsafe = post("/api/message", "{\"text\": \"/webping busy\"}");
        assertTrue(unsafe.body().contains("执行中，需等待空闲"),
                "非 busySafe 明确等待提示: " + unsafe.body());

        Thread.sleep(2000); // 等 slow agent 跑完再关 face，避免竞态
    }

    @Test
    void skillInvocationStillGoesToAgentWithPrefixInjection() throws Exception {
        // 技能直调在 Web 同样成立（两表一入口顺序双面一致）：命令表未命中 → 技能
        // 前缀注入走 agent（进模型历史）——/release-notes 为仓库 .agents/skills 实技能
        Session session = Session.create(tempDir.resolve("sessions"));
        List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        start(session, (userText, listener) -> {
            sent.add(userText);
            return new AgentReply("答", List.of(), true);
        });
        mountCommands(true);

        HttpResponse<String> res = post("/api/message",
                "{\"text\": \"/release-notes 0.3.0\"}");
        assertEquals(202, res.statusCode());
        assertEquals("", res.body(), "技能直调落普通受理（前端进思考态）");
        long deadline = System.currentTimeMillis() + 5_000;
        while (sent.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("用户输入：0.3.0"),
                "技能指令前缀注入文本照旧进模型历史: " + sent.get(0));
    }

    @Test
    void pageSizeConfigDrivesSnapshotWindow() throws Exception {
        // 页长可配（M19 还账，ADR-0020 决策 12）：pageSize=3 —— 6 条投影消息的会话
        // 首屏快照只回 3 条（从第 2 条消息起），头帧 hasMore=true、更早计数=3
        Session session = Session.create(tempDir.resolve("sessions"));
        for (int i = 0; i < 3; i++) {
            session.append(SessionEvent.userMessage("问" + i));
            session.append(SessionEvent.assistantMessage("答" + i));
        }
        Context ctx = Context.root();
        faceCtx = ctx;
        ctx.plugin(new ToolsPlugin(), null).awaitStartup();
        ToolsService tools = ctx.as(ToolsView.class).tools();
        face = WebFace.start(0, ctx, tools, session,
                (userText, listener) -> new AgentReply("ok", List.of(), true), null, null,
                tempDir.resolve("web-sessions"), 3);
        face.onNewSession(() -> Session.create(tempDir.resolve("web-sessions")));
        face.onSessionChanged(changed -> changedSessions.add(changed));

        String stream;
        try (SseCollector sse = openSse(null)) {
            stream = sse.awaitText(800);
        }
        assertTrue(stream.contains("\"hasMore\":true") && stream.contains("\"earlierCount\":3"),
                "页长 3 生效（6 条投影取尾 3）: " + stream);
        assertTrue(stream.contains("答1"), "窗口首条为第 4 条消息（尾 3 条之首）");
        assertTrue(!stream.contains("问1") && !stream.contains("问0"),
                "窗口外消息不下发: " + stream);
    }

    @Test
    void iterationCapPushesRunErrorFrame() throws Exception {
        // 迭代上限可见化（ADR-0018 搭车，BUG-20260917-03 验收 B 的缺口）：agent 返回
        // completed=false 时 Web 面直推 run/error 错误卡——页面不再无提示地停住
        Session session = Session.create(tempDir.resolve("sessions"));
        ChatAgent capped = (userText, listener) -> new AgentReply(
                "已达最大迭代轮数（10），共执行 9 次工具调用", List.of(), false);
        start(session, capped);

        String stream;
        try (SseCollector sse = openSse(null)) {
            HttpResponse<String> ack = post("/api/message", "{\"text\": \"做个大任务\"}");
            assertEquals(202, ack.statusCode());
            stream = sse.awaitText(800);
        }

        assertTrue(stream.contains("\"type\":\"run/error\""),
                "未完成终止直推 run/error 帧: " + stream);
        assertTrue(stream.contains("已达最大迭代轮数"),
                "错误卡携带失败说明（页面可见原因）: " + stream);
    }

    @Test
    void todoWriteEventStreamsToFrontend() throws Exception {
        // todo/write 数据通道（ADR-0018，工单 04）：事件经 SSE 推送/回放给前端——
        // 常驻面板与工具行摘要的单一数据源，前端无独立拉取端点
        Session session = Session.create(tempDir.resolve("sessions"));
        session.append(SessionEvent.userMessage("多步任务"));
        session.append(SessionEvent.todoWrite(
                "[{\"content\":\"调研现状\",\"status\":\"completed\"},{\"content\":\"写方案\",\"status\":\"in_progress\"}]"));
        session.append(SessionEvent.assistantMessage("按清单推进"));
        start(session, scriptedAgent(session, "ok"));

        String stream;
        try (SseCollector sse = openSse(null)) {
            stream = sse.awaitText(800);
        }

        assertTrue(stream.contains("\"type\":\"todo/write\""),
                "todo/write 事件帧在场（尾窗快照含非投影事件）: " + stream);
        assertTrue(stream.contains("写方案"), "清单载荷完整往返: " + stream);
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
        other.close(); // 准备完毕即释放锁：模拟"会话文件存在、当前无进程占用"（可被 Web 面打开）
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
    void sessionSwitchToOccupiedSessionReportsConflict() throws Exception {
        // 占用冲突（工单 M10-03）：切到被其他实例持有的会话，给出 409 + 点名会话的明确错误
        Path listed = tempDir.resolve("web-sessions");
        Session occupied = Session.create(listed);
        occupied.append(SessionEvent.userMessage("被占会话"));
        Session current = Session.create(tempDir.resolve("sessions"));
        start(current, scriptedAgent(current, "ok"));

        HttpResponse<String> response = post("/api/session/switch",
                "{\"id\": \"" + occupied.id() + "\"}");

        assertEquals(409, response.statusCode(), "占用冲突用 409（与 404 不存在区分）");
        assertTrue(response.body().contains("已被占用"), response.body());
        assertTrue(response.body().contains(occupied.id()), "点名被占会话: " + response.body());
        assertEquals(current.id(), face.currentSession().id(), "冲突时当前会话不变");
        occupied.close();
    }

    @Test
    void sessionSwitchToCurrentSessionIsIdempotent() throws Exception {
        // 切到当前会话幂等成功（双轴审查修复）：重新 load 自己必撞独占锁，
        // 而语义上本就无需动作——侧栏点当前项、重复提交切换请求都不该失败
        Session session = Session.create(tempDir.resolve("sessions"));
        start(session, scriptedAgent(session, "ok"));

        HttpResponse<String> response = post("/api/session/switch",
                "{\"id\": \"" + session.id() + "\"}");

        assertEquals(200, response.statusCode(), "切到当前会话应幂等成功: " + response.body());
        assertEquals(session.id(), face.currentSession().id(), "当前会话不变");
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

    @Test
    void sessionsJsonCarriesOccupiedField() throws Exception {
        // 占用标注（工单 M13-05）：每行携 occupied；当前会话（本进程持锁）标 true——
        // 标注只提供预期，点击语义不变
        Path listed = tempDir.resolve("web-sessions");
        Session first = Session.create(listed);
        first.append(SessionEvent.userMessage("历史"));
        first.append(SessionEvent.title("历史会话标题"));
        first.close(); // 空闲态夹具：探测应报 occupied=false（工单 M13-05 两态断言）
        Session current = Session.create(listed);
        start(current, scriptedAgent(current, "ok"));

        JsonNode json = new ObjectMapper().readTree(get("/api/sessions"));
        boolean freeSeen = false;
        boolean currentOccupied = false;
        String firstTitle = null;
        for (JsonNode node : json.path("sessions")) {
            assertTrue(node.has("occupied"), "每行携 occupied 字段");
            assertTrue(node.has("title"), "每行携 title 字段（工单 M13-06）");
            if (node.path("occupied").asBoolean()) {
                if (node.path("id").asText().equals(current.id())) {
                    currentOccupied = true;
                }
            } else if (node.path("id").asText().equals(first.id())) {
                freeSeen = true;
            }
            if ("历史会话标题".equals(node.path("title").asText())) {
                firstTitle = node.path("title").asText();
            }
        }
        assertTrue(currentOccupied, "当前会话（本进程持锁）= true");
        assertTrue(freeSeen, "已关闭会话 = false（占用/空闲两态）");
        assertEquals("历史会话标题", firstTitle, "侧栏行带会话标题");
    }

    /**
     * HITL 语义用例装配：经装配好的交互 seam（生产同款接线）在虚拟线程发起一条
     * 审批请求，阻塞等待作答；返回时可保证 answerer 已进入待答态。
     */
    private Thread askApproval(WebAnswerer webAnswerer,
                               AtomicReference<dev.duo.harness.tools.InteractionAnswer> got,
                               CountDownLatch done) throws InterruptedException {
        dev.duo.harness.tools.InteractionService answers = faceCtx.as(AnswersView.class).answers();
        Thread thread = Thread.ofVirtual().start(() -> {
            got.set(answers.ask(dev.duo.harness.tools.InteractionRequest.approval("write_file", "{}")));
            done.countDown();
        });
        while (webAnswerer.currentPending() == null) {
            Thread.sleep(20);
        }
        return thread;
    }

    @Test
    void allClientsGoneFailsClosedAfterGracePeriod() throws Exception {
        // fail-closed 语义钉死（工单 M10-04）：全部连接离场 → 宽限后仍无人 → 悬空审批拒绝；
        // 宽限期内不得立即拒绝（去抖保护刷新窗口）
        Session session = Session.create(tempDir.resolve("sessions"));
        WebAnswerer webAnswerer = new WebAnswerer(60_000);
        start(session, scriptedAgent(session, "ok"), null, webAnswerer);
        AtomicReference<dev.duo.harness.tools.InteractionAnswer> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        askApproval(webAnswerer, got, done);

        // 摘除死连接（写失败后的摘除时点）→ 列表空 → 进入宽限
        face.removeClient(new WebFace.SseClient(new java.io.ByteArrayOutputStream()));
        Thread.sleep(500);
        assertTrue(webAnswerer.currentPending() != null, "宽限期内不得立即拒绝");
        assertTrue(done.await(WebFace.FAIL_CLOSED_GRACE_MS + 1_500, TimeUnit.MILLISECONDS),
                "宽限到期后完成");
        assertFalse(got.get().approved(), "仍无连接 → 悬空审批按拒绝处理");
        assertEquals(dev.duo.harness.tools.InteractionAnswer.SOURCE_FAIL_CLOSED, got.get().source());
    }

    @Test
    void refreshReconnectWithinGraceKeepsApprovalAnswerable() throws Exception {
        // 刷新"断旧立新"：旧连接摘除开启宽限 → 新连接窗口内入列 → 审批保持可答不被误杀，
        // 且新连接回放含 approval/requested（页面据此重建审批卡）
        Session session = Session.create(tempDir.resolve("sessions"));
        WebAnswerer webAnswerer = new WebAnswerer(60_000);
        start(session, scriptedAgent(session, "ok"), null, webAnswerer);
        AtomicReference<dev.duo.harness.tools.InteractionAnswer> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        askApproval(webAnswerer, got, done);

        // 旧连接摘除 → 宽限窗口开启；刷新后的新连接窗口内入列
        face.removeClient(new WebFace.SseClient(new java.io.ByteArrayOutputStream()));
        HttpResponse<java.io.InputStream> reconnect = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + face.port() + "/api/events")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var body = reconnect.body()) {
            for (int i = 0; i < 50 && face.sseConnections() == 0; i++) {
                Thread.sleep(20);
            }
            assertEquals(1, face.sseConnections(), "新连接已入列");
            Thread.sleep(WebFace.FAIL_CLOSED_GRACE_MS + 500);
            assertTrue(webAnswerer.currentPending() != null, "宽限到期仍有连接 → 不误杀");
            assertTrue(webAnswerer.complete(true, List.of()), "审批仍可作答");
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertTrue(got.get().approved());
            // 回放契约：新连接首屏流含审批请求事件（前端据此重建审批卡，工单 checklist 第 3 条）。
            // SSE 长连接不结束——读线程逐块累积已到达字节，主线程限时取回后关流打断
            AtomicReference<String> replay = new AtomicReference<>("");
            Thread reader = Thread.ofVirtual().start(() -> {
                byte[] chunk = new byte[1_024];
                StringBuilder collected = new StringBuilder();
                try {
                    int read;
                    while ((read = body.read(chunk)) != -1) {
                        collected.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                        replay.set(collected.toString());
                    }
                } catch (Exception e) {
                    // 关流打断：已累积字节保留在 reference 中
                }
            });
            Thread.sleep(800);
            body.close();
            reader.interrupt();
            assertTrue(replay.get().contains("approval/requested"),
                    "新连接回放应含 approval/requested（审批卡数据源）: "
                            + replay.get().substring(0, Math.min(300, replay.get().length())));
        }
    }

    /** HITL 语义用例装配：在虚拟线程发起一条提问（ask_user 形态），阻塞等待作答。 */
    private Thread askQuestion(WebAnswerer webAnswerer,
                               AtomicReference<dev.duo.harness.tools.InteractionAnswer> got,
                               CountDownLatch done) throws InterruptedException {
        dev.duo.harness.tools.InteractionService answers = faceCtx.as(AnswersView.class).answers();
        Thread thread = Thread.ofVirtual().start(() -> {
            got.set(answers.ask(dev.duo.harness.tools.InteractionRequest.question(
                    "文件存哪？", List.of("A", "B"), false)));
            done.countDown();
        });
        while (webAnswerer.currentPending() == null) {
            Thread.sleep(20);
        }
        return thread;
    }

    @Test
    void answerEndpointDecisionCompletesApproval() throws Exception {
        // 结构化协议（M16 工单 07）：审批卡 {decision: approve|reject} → InteractionAnswer.approved
        Session session = Session.create(tempDir.resolve("sessions"));
        WebAnswerer webAnswerer = new WebAnswerer(60_000);
        start(session, scriptedAgent(session, "ok"), null, webAnswerer);

        AtomicReference<dev.duo.harness.tools.InteractionAnswer> approveGot = new AtomicReference<>();
        CountDownLatch approveDone = new CountDownLatch(1);
        askApproval(webAnswerer, approveGot, approveDone);
        HttpResponse<String> approve = post("/api/answer", "{\"decision\":\"approve\"}");
        assertEquals(200, approve.statusCode());
        assertEquals("{\"completed\":true}", approve.body());
        assertTrue(approveDone.await(2, TimeUnit.SECONDS));
        assertTrue(approveGot.get().approved(), "approve → approved=true");

        AtomicReference<dev.duo.harness.tools.InteractionAnswer> rejectGot = new AtomicReference<>();
        CountDownLatch rejectDone = new CountDownLatch(1);
        askApproval(webAnswerer, rejectGot, rejectDone);
        assertEquals(200, post("/api/answer", "{\"decision\":\"reject\"}").statusCode());
        assertTrue(rejectDone.await(2, TimeUnit.SECONDS));
        assertFalse(rejectGot.get().approved(), "reject → approved=false");
        assertEquals(List.of(), rejectGot.get().values(), "审批形态不带 answers");
    }

    @Test
    void answerEndpointRefusalTextIsPlainAnswer() throws Exception {
        // 回归（质量审查 M5 / M16 工单 07）：自由文本"拒绝"是普通回答——旧协议按魔法串
        // 判定会把提问答成审批拒绝；结构化协议下提问形态 approved 恒 true、文本原样传递
        Session session = Session.create(tempDir.resolve("sessions"));
        WebAnswerer webAnswerer = new WebAnswerer(60_000);
        start(session, scriptedAgent(session, "ok"), null, webAnswerer);

        AtomicReference<dev.duo.harness.tools.InteractionAnswer> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        askQuestion(webAnswerer, got, done);

        HttpResponse<String> response = post("/api/answer", "{\"answers\":[\"拒绝\"]}");
        assertEquals(200, response.statusCode());
        assertEquals("{\"completed\":true}", response.body());
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(got.get().approved(), "提问的\"拒绝\"文本 → approved=true（不误判为审批拒绝）");
        assertEquals(List.of("拒绝"), got.get().values(), "文本原样传递");
    }

    @Test
    void answerEndpointRejectsMalformedAndLegacyBodies() throws Exception {
        // 无兼容层（工单 07 裁定直接切）：旧协议体、非法 decision 值、空 answers、无字段
        // 一律 400，且不完成悬空请求（判定只看结构化字段，不做字符串嗅探）
        Session session = Session.create(tempDir.resolve("sessions"));
        WebAnswerer webAnswerer = new WebAnswerer(60_000);
        start(session, scriptedAgent(session, "ok"), null, webAnswerer);

        AtomicReference<dev.duo.harness.tools.InteractionAnswer> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        askApproval(webAnswerer, got, done);

        for (String body : List.of("{\"approved\":true}", "{\"decision\":\"maybe\"}",
                "{\"answers\":[]}", "{}")) {
            assertEquals(400, post("/api/answer", body).statusCode(), "非法体应 400: " + body);
        }
        assertTrue(webAnswerer.currentPending() != null, "非法作答不完成悬空请求");
        assertTrue(webAnswerer.complete(true, List.of()), "悬空请求仍可正常作答");
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(got.get().approved());
    }

    @Test
    void entryGateBlocksForgedHostAndCrossOriginPosts() throws Exception {
        // 入口栅栏（M16 工单 02）：DNS rebinding（伪造 Host）→ 403；跨站 POST（恶意
        // Origin）→ 403；本地 curl 形态（无 Origin）与同源请求不受影响。
        // 伪造 Host 用 raw socket——java.net.http.HttpClient 的 Host 头受限不可伪造
        Session session = Session.create(tempDir.resolve("sessions"));
        start(session, scriptedAgent(session, "ok"));

        String forged = rawGet("evil.com", "/api/status");
        assertTrue(forged.startsWith("HTTP/1.1 403"), "伪造 Host → 403: " + forged.split("\r\n", 2)[0]);
        String localhost = rawGet("localhost:" + face.port(), "/api/status");
        assertTrue(localhost.startsWith("HTTP/1.1 200"), "白名单内 localhost Host 放行");

        // Origin 矩阵用非异步端点（/api/session/new），避免 /api/message 单飞 409 噪声
        assertEquals(403, post("/api/session/new", "{}", "http://evil.com").statusCode(),
                "跨站 Origin 拒绝");
        assertEquals(200, post("/api/session/new", "{}").statusCode(),
                "无 Origin（本地 curl 形态）放行");
        assertEquals(200, post("/api/session/new", "{}",
                "http://127.0.0.1:" + face.port()).statusCode(), "同源 Origin 放行");
        assertEquals(202, post("/api/message", "{\"text\":\"hi\"}").statusCode(),
                "对话入口无 Origin 放行");
    }

    @Test
    void entryGateDoesNotCheckOriginOnGet() throws Exception {
        // GET/SSE 无副作用不校验 Origin——带恶意 Origin 的 GET 照常放行（Host 已兜底）
        Session session = Session.create(tempDir.resolve("sessions"));
        start(session, scriptedAgent(session, "ok"));

        assertEquals(200, fetch("/api/status", "http://evil.com").statusCode(),
                "GET 不校验 Origin");
    }

    /**
     * 原始 socket GET（栅栏用例专用）：java.net.http.HttpClient 的 Host 头属受限头
     * 不可伪造，伪造 Host 的攻击形态只能经裸 socket 发送；Connection: close 由
     * 服务端在响应后关连接，readAllBytes 到 EOF 即完整响应。
     */
    private String rawGet(String hostHeader, String path) throws IOException {
        try (var socket = new java.net.Socket("127.0.0.1", face.port())) {
            socket.setSoTimeout(5_000);
            var out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + hostHeader
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * SSE 采集器（测试夹具）：后台线程逐块累积流输出，{@link #close()} 关流打断。
     * SSE 长连接不结束——读取必须非阻塞，限时取回已到达部分。
     */
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

        /** 等待至多 millis，返回当前累积文本。 */
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

    /** 连 /api/events（可带 Last-Event-ID 游标头）并返回采集器。 */
    private SseCollector openSse(String lastEventId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + face.port() + "/api/events"));
        if (lastEventId != null) {
            builder.header("Last-Event-ID", lastEventId);
        }
        return new SseCollector(client.send(builder.GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()).body());
    }

    @Test
    void sseCursorAtLatestDeliversEmptyIncrement() throws Exception {
        // 游标指向最后一条（客户端已追平）→ 增量模式但零补发（仅边界帧）——
        // 追平是重连后的常见稳态，不能被误判成需要全量重放
        Session session = Session.create(tempDir.resolve("sessions"));
        session.append(SessionEvent.userMessage("第一问"));
        session.append(SessionEvent.assistantMessage("第一答"));
        start(session, scriptedAgent(session, "ok"));

        String stream;
        try (SseCollector sse = openSse("1")) {
            stream = sse.awaitText(600);
        }

        assertTrue(stream.contains("\"type\":\"replay/start\",\"mode\":\"incremental\""),
                "追平游标仍为增量模式: " + stream);
        assertTrue(stream.contains("\"type\":\"replay/done\""), "边界帧在场");
        assertTrue(!stream.contains("\"type\":\"user/message\"")
                        && !stream.contains("\"type\":\"assistant/message\""),
                "无缺失段则不补发任何事件: " + stream);
    }

    @Test
    void sseTailSnapshotCarriesHeaderAndEventIds() throws Exception {
        // 尾部窗口快照（工单 M13-01 / ADR-0013）：首连无游标 → tail-snapshot 头帧（携 hasMore
        // 与更早计数）+ 窗口事件帧（带日志序号 id）；边界帧（replay/start、replay/done）不带 id
        Session session = Session.create(tempDir.resolve("sessions"));
        session.append(SessionEvent.userMessage("第一问"));
        session.append(SessionEvent.assistantMessage("第一答"));
        start(session, scriptedAgent(session, "ok"));

        String stream;
        try (SseCollector sse = openSse(null)) {
            stream = sse.awaitText(800);
        }

        assertTrue(stream.contains("\"mode\":\"tail-snapshot\""), "首连为尾部窗口快照模式: " + stream);
        assertTrue(stream.contains("\"hasMore\":false") && stream.contains("\"earlierCount\":0"),
                "不足窗口上限：无更早消息: " + stream);
        assertTrue(stream.contains("id: 0\n") && stream.contains("id: 1\n"),
                "窗口未截断时事件帧从 0 起全量: " + stream);
        assertTrue(stream.contains("user/message") && stream.contains("assistant/message"), "事件内容在场");
        assertTrue(stream.contains("\"type\":\"replay/done\""), "边界帧在场");
        // 边界帧自带帧头（\n\ndata: 紧跟 JSON）：若被加上 id 行则格式变为 \n\nid: N\ndata: ...
        assertTrue(stream.contains("\n\ndata: {\"type\":\"replay/start\""),
                "replay/start 帧不带序号: " + stream);
        assertTrue(stream.contains("\n\ndata: {\"type\":\"replay/done\"}"),
                "replay/done 帧不带序号: " + stream);
    }

    @Test
    void sseTailSnapshotStartsAtProjectedBoundary() throws Exception {
        // 投影边界映射（工单 M13-01 正确性核心）：60 条投影消息取尾 50，事件帧从第 10 条
        // 消息（0 基，即"问5"）的事件下标 10 起发，头帧 hasMore=true、更早计数=10
        Session session = Session.create(tempDir.resolve("sessions"));
        for (int i = 0; i < 30; i++) {
            session.append(SessionEvent.userMessage("问" + i));
            session.append(SessionEvent.assistantMessage("答" + i));
        }
        start(session, scriptedAgent(session, "ok"));

        String stream;
        try (SseCollector sse = openSse(null)) {
            stream = sse.awaitText(800);
        }

        assertTrue(stream.contains("\"mode\":\"tail-snapshot\""), "尾部快照模式: " + stream);
        assertTrue(stream.contains("\"hasMore\":true") && stream.contains("\"earlierCount\":10"),
                "窗口被截断：头帧携截断信息: " + stream);
        assertTrue(stream.contains("id: 10\n"), "首帧从边界事件（下标 10）起: " + stream);
        assertTrue(!stream.contains("id: 9\n"), "边界之前的事件不下发: " + stream);
        assertTrue(stream.contains("问5") && stream.contains("答5"), "窗口首条消息（消息序数 10）在场");
        assertTrue(!stream.contains("问4") && !stream.contains("答4"), "窗外消息（消息 0-9）不出现");
        assertTrue(stream.contains("id: 59\n"), "末帧到日志末尾: " + stream);
    }

    @Test
    void sseIncrementalReplayFromLastEventId() throws Exception {
        // 断线重连（带 Last-Event-ID）→ 增量模式：只补其后的缺失段，已渲染的存量不重发
        Session session = Session.create(tempDir.resolve("sessions"));
        session.append(SessionEvent.userMessage("第一问"));
        session.append(SessionEvent.assistantMessage("第一答"));
        session.append(SessionEvent.userMessage("第二问"));
        start(session, scriptedAgent(session, "ok"));

        String stream;
        try (SseCollector sse = openSse("1")) {
            stream = sse.awaitText(800);
        }

        assertTrue(stream.contains("\"type\":\"replay/start\",\"mode\":\"incremental\""),
                "带游标为增量模式: " + stream);
        assertTrue(stream.contains("id: 2\n"), "补发游标之后的事件: " + stream);
        assertTrue(!stream.contains("id: 0\n") && !stream.contains("id: 1\n"), "存量不重发: " + stream);
        assertTrue(stream.contains("第二问"), "补发内容为缺失段");
        assertTrue(!stream.contains("第一问") && !stream.contains("第一答"), "已渲染历史不重发");
    }

    @Test
    void sseOutOfRangeOrInvalidCursorFallsBackToTailSnapshot() throws Exception {
        // 游标越界 / 非法 → 尾部窗口快照兜底（与首连同路径，ADR-0013）
        Session session = Session.create(tempDir.resolve("sessions"));
        session.append(SessionEvent.userMessage("第一问"));
        session.append(SessionEvent.assistantMessage("第一答"));
        start(session, scriptedAgent(session, "ok"));

        for (String cursor : List.of("99", "abc", "-3")) {
            String stream;
            try (SseCollector sse = openSse(cursor)) {
                stream = sse.awaitText(600);
            }
            assertTrue(stream.contains("\"mode\":\"tail-snapshot\""),
                    "游标 " + cursor + " 回退尾部快照: " + stream);
            assertTrue(stream.contains("id: 0\n") && stream.contains("id: 1\n"),
                    "游标 " + cursor + " 按尾部窗口重发: " + stream);
        }
    }

    @Test
    void sseLiveFramesCarryEventIds() throws Exception {
        // 实时广播帧同样带序号：浏览器以最后收到的 id 作为重连游标——
        // 实时帧缺 id 会让重连从旧游标补发，已渲染的事件重复呈现
        Session session = Session.create(tempDir.resolve("sessions"));
        start(session, scriptedAgent(session, "ok"));

        try (SseCollector sse = openSse(null)) {
            sse.awaitText(300); // 空会话回放完成
            session.append(SessionEvent.userMessage("实时消息"));
            String stream = sse.awaitText(600);
            assertTrue(stream.contains("id: 0\n"), "实时帧带序号: " + stream);
            assertTrue(stream.contains("实时消息"), "实时帧内容在场: " + stream);
        }
    }

    @Test
    void pageEndpointWindowAndHasMore() throws Exception {
        // 分页端点（工单 M13-02）：before 之前的尾页事件 + 窗口头——窗口数学与 tail-snapshot 同源
        Session session = Session.create(tempDir.resolve("sessions"));
        for (int i = 0; i < 30; i++) {
            session.append(SessionEvent.userMessage("问" + i));
            session.append(SessionEvent.assistantMessage("答" + i));
        }
        start(session, scriptedAgent(session, "ok"));

        com.fasterxml.jackson.databind.JsonNode page = new ObjectMapper().readTree(
                get("/api/session/page?before=60"));

        org.junit.jupiter.api.Assertions.assertEquals(10, page.get("startEvent").asInt(), "窗口起点");
        org.junit.jupiter.api.Assertions.assertEquals(50, page.get("events").size(), "每页 50 条事件");
        org.junit.jupiter.api.Assertions.assertTrue(page.get("hasMore").asBoolean(), "仍有更早历史");
        org.junit.jupiter.api.Assertions.assertEquals(10, page.get("earlierCount").asInt(), "更早计数");
        org.junit.jupiter.api.Assertions.assertEquals("问5", page.get("events").get(0).get("text").asText(),
                "窗口首条消息与 SSE 尾部快照同源");
    }

    @Test
    void pageEndpointHasMoreFlipsAtLogHead() throws Exception {
        // hasMore 翻转：before 落在窗口起点 → 一页取尽、更早清零
        Session session = Session.create(tempDir.resolve("sessions"));
        for (int i = 0; i < 30; i++) {
            session.append(SessionEvent.userMessage("问" + i));
            session.append(SessionEvent.assistantMessage("答" + i));
        }
        start(session, scriptedAgent(session, "ok"));

        com.fasterxml.jackson.databind.JsonNode page = new ObjectMapper().readTree(
                get("/api/session/page?before=10"));

        org.junit.jupiter.api.Assertions.assertEquals(0, page.get("startEvent").asInt(), "不足一页 → 从 0 起");
        org.junit.jupiter.api.Assertions.assertEquals(10, page.get("events").size());
        org.junit.jupiter.api.Assertions.assertFalse(page.get("hasMore").asBoolean(), "日志头之前无更早");
        org.junit.jupiter.api.Assertions.assertEquals(0, page.get("earlierCount").asInt());
    }

    @Test
    void pageEndpointRejectsBadOrOutOfRangeBefore() throws Exception {
                Session session = Session.create(tempDir.resolve("sessions"));
        start(session, scriptedAgent(session, "ok"));

        assertEquals(400, fetch("/api/session/page?before=abc").statusCode(), "非数字 before");
        assertEquals(400, fetch("/api/session/page?before=1").statusCode(), "越界 before（空会话上界为 0）");
        assertEquals(400, fetch("/api/session/page").statusCode(), "缺 before 参数");
    }

    @Test
    void pageEndpointPaginationCoversLogWithoutGaps() throws Exception {
        // 分页连续性：首页 [10,60) + 次页 [0,10) 拼回全量，无缺口无重叠（缺口修复语义）
        Session session = Session.create(tempDir.resolve("sessions"));
        for (int i = 0; i < 30; i++) {
            session.append(SessionEvent.userMessage("问" + i));
            session.append(SessionEvent.assistantMessage("答" + i));
        }
        start(session, scriptedAgent(session, "ok"));

        com.fasterxml.jackson.databind.JsonNode first = new ObjectMapper().readTree(get("/api/session/page?before=60"));
        com.fasterxml.jackson.databind.JsonNode second = new ObjectMapper().readTree(
                get("/api/session/page?before=" + first.get("startEvent").asInt()));

        org.junit.jupiter.api.Assertions.assertEquals(60,
                first.get("events").size() + second.get("events").size(), "两页拼回全量 60 条");
        org.junit.jupiter.api.Assertions.assertEquals("问0", second.get("events").get(0).get("text").asText(),
                "次页首条 = 日志头");
        org.junit.jupiter.api.Assertions.assertEquals("答4", second.get("events").get(9).get("text").asText(),
                "次页末条与首页首条无缝相接");
    }

    @Test
    void sseErrorFrameCarriesNoId() throws Exception {
        // 非会话帧（run/error 不落会话日志）不带 id：无日志下标可锚（ADR-0010 决策 1），
        // 带上别的 id 会污染浏览器游标（客户端会误以为该序号的日志事件已收到）
        Session session = Session.create(tempDir.resolve("sessions"));
        ChatAgent failing = (userText, listener) -> {
            throw new IllegalStateException("模拟执行故障");
        };
        start(session, failing);

        try (SseCollector sse = openSse(null)) {
            sse.awaitText(300);
            post("/api/message", "{\"text\": \"触发故障\"}");
            String stream = sse.awaitText(1_000);
            assertTrue(stream.contains("run/error"), "错误帧在场: " + stream);
            assertTrue(stream.contains("\n\ndata: {\"type\":\"run/error\""),
                    "错误帧不带序号: " + stream);
        }
    }

    @Test
    void concurrentSwitchesLeakNoSessionLocks() throws Exception {
        // 换绑串行化回归（工单 M10-03 验收发现）：并发切换下"关闭上一个"链条必须线性——
        // 各关各的快照会跳过中间会话，其独占锁永久泄漏（之后切到它永远 409）。
        // 压力后不变量：除当前会话外，其余会话文件必须全部可加锁（无泄漏）。
        Path listed = tempDir.resolve("web-sessions");
        Session a = Session.create(listed);
        a.append(SessionEvent.userMessage("A"));
        Session b = Session.create(listed);
        b.append(SessionEvent.userMessage("B"));
        a.close();
        b.close();
        Session current = Session.create(listed);
        current.append(SessionEvent.userMessage("初始"));
        start(current, scriptedAgent(current, "ok"));

        List<String> targets = List.of(a.id(), b.id(), current.id());
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<Thread> threads = new java.util.ArrayList<>();
        List<AssertionError> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        for (int i = 0; i < 6; i++) {
            String target = targets.get(i % targets.size());
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    HttpResponse<String> response = post("/api/session/switch",
                            "{\"id\": \"" + target + "\"}");
                    if (response.statusCode() != 200 && response.statusCode() != 409) {
                        failures.add(new AssertionError(
                                "切换 " + target + " 异常状态 " + response.statusCode()));
                    }
                } catch (Exception e) {
                    failures.add(new AssertionError(e));
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
        }
        assertTrue(failures.isEmpty(), () -> "并发切换出现异常: " + failures);

        // 不变量：全部并发结束后，除当前会话外其余会话必须可加锁（无泄漏的独占锁）
        String currentId = face.currentSession().id();
        for (String id : targets) {
            if (id.equals(currentId)) {
                continue;
            }
            try (FileChannel probe = FileChannel.open(
                    listed.resolve(id + ".jsonl"), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                java.nio.channels.FileLock lock = probe.tryLock();
                assertTrue(lock != null, "会话 " + id + " 的独占锁被泄漏（非当前会话却不可加锁）");
                if (lock != null) {
                    lock.release();
                }
            }
        }
    }

    /** 单段直答 mock agent（模拟真实 ToolCallingAgent：user 消息入会话 + chunk 交 listener）。 */
    private ChatAgent scriptedAgent(Session session, String reply) {
        return (userText, listener) -> {
            session.append(SessionEvent.userMessage(userText));
            listener.onChunk(reply);
            return new AgentReply(reply, List.of(), true);
        };
    }

    @Test
    void subagentReplayServesEventsSkipsBadLinesAndRejectsBadIds() throws Exception {
        // 子任务回放端点（M15 工单 05）：静态只读——事件原样返回、坏行跳过；
        // id 白名单拒绝路径穿越；不存在的子会话返回 found=false 而非 404（前端弹层统一呈现）
        Path sessionsDir = tempDir.resolve("web-sessions");
        Path subagentsDir = sessionsDir.resolve("subagents");
        java.nio.file.Files.createDirectories(subagentsDir);
        String childId = "20260916-120000-00ff";
        java.nio.file.Files.write(subagentsDir.resolve(childId + ".jsonl"), List.of(
                "{\"type\":\"user/message\",\"at\":1,\"text\":\"播种的父背景\"}",
                "{\"type\":\"subagent/seed-boundary\",\"at\":2,\"text\":\"前 1 条来自父会话 p-1\"}",
                "not-a-json-line",
                "{\"type\":\"assistant/message\",\"at\":3,\"text\":\"子代理结论\"}"));
        Session parent = Session.create(sessionsDir);
        start(parent, scriptedAgent(parent, "好"));

        HttpResponse<String> ok = fetch("/api/subagent/events?id=" + childId);
        assertEquals(200, ok.statusCode(), "正常读取");
        JsonNode node = new ObjectMapper().readTree(ok.body());
        assertTrue(node.get("found").asBoolean());
        assertEquals(3, node.get("events").size(), "坏行跳过，其余事件原样返回");
        assertEquals("播种的父背景", node.get("events").get(0).get("text").asText());
        assertEquals("subagent/seed-boundary", node.get("events").get(1).get("type").asText(),
                "fork 播种背景段在子会话回放中可见");

        assertEquals(400, fetch("/api/subagent/events?id=../../etc/passwd").statusCode(),
                "路径穿越形态拒绝");
        assertEquals(400, fetch("/api/subagent/events?id=short").statusCode(),
                "白名单外 id 拒绝");
        HttpResponse<String> missing = fetch("/api/subagent/events?id=20260916-120000-00aa");
        assertEquals(200, missing.statusCode(), "合法 id 不存在的文件不 404");
        assertFalse(new ObjectMapper().readTree(missing.body()).get("found").asBoolean(),
                "found=false 由前端统一呈现");
    }
}
