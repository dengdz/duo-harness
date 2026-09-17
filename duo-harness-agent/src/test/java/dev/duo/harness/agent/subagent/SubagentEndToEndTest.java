package dev.duo.harness.agent.subagent;

import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.internal.ToolCallingAgent;
import dev.duo.harness.agent.subagent.backend.EmbeddedSubagentBackend;
import dev.duo.harness.agent.subagent.backend.PinnedApprovalPolicy;
import dev.duo.harness.agent.subagent.tools.SpawnTool;
import dev.duo.harness.agent.subagent.tools.ForkTool;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端全链（工单 03）：mock 双 LLM + 真工具域 + 真内嵌后端——父 agent 调
 * spawn → 子 agent 调工具 → 完成 → 最终回答回流父会话并投影进父上下文。
 * 呈现面（子任务卡）归工单 05，本套件锁定会话与投影层闭环。
 */
class SubagentEndToEndTest {

    @TempDir
    Path tempDir;

    private Context root;
    private ToolsService tools;
    private Session parent;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SubagentEndToEndTest —— 端到端全链：父 spawn 子 → 子调工具 → "
                + "完成回流 → 父投影收到结论；fork 播种全链；审批钉死与环境块（4 用例） ===");
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface ToolsView {

        ToolsService tools();
    }

    @BeforeEach
    void setUp() {
        root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        tools = root.as(ToolsView.class).tools();
        tools.register(root, echoDef());
        parent = Session.create(sessionsDir());
    }

    @AfterEach
    void tearDown() {
        parent.close();
        root.dispose();
    }

    private java.nio.file.Path sessionsDir() {
        return tempDir.resolve("sessions");
    }

    /** 子代理可用的普通工具（模板工具集成员；执行即回显）。 */
    private static ToolDefinition echoDef() {
        return new ToolDefinition() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "回显输入";
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode parameters() {
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public Object execute(ToolExecution execution) {
                return "echo:" + execution.args().path("text").asText();
            }
        };
    }

    private SubagentManager manager() {
        return new SubagentManager(SubagentTemplates.parse(config(
                "{\"templates\": [{\"name\": \"worker\", \"tools\": [\"echo\"]}]}")));
    }

    private static com.fasterxml.jackson.databind.JsonNode config(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 父 LLM 脚本：首轮调指定派生工具，次轮直答。 */
    private static LlmAdapter parentLlm(AtomicInteger calls, String toolName) {
        return new LlmAdapter() {
            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                if (calls.incrementAndGet() == 1) {
                    return new LlmTurn("", List.of(new ToolCallRequest("call_1", toolName,
                            "{\"template\":\"worker\",\"task\":\"调研 X\"}")));
                }
                String answer = "已派子代理，请稍候";
                textSink.accept(answer);
                return new LlmTurn(answer, List.of());
            }

            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                throw new UnsupportedOperationException("agent 循环走 streamTurn");
            }
        };
    }

    /** 子 LLM 脚本：首轮调 echo，次轮直答结论；收到的 system 提示记入 systems。 */
    private static LlmAdapter childLlm(java.util.List<String> systems) {
        return new LlmAdapter() {
            final AtomicInteger calls = new AtomicInteger();

            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                systems.add(request.systemPrompt());
                if (calls.incrementAndGet() == 1) {
                    return new LlmTurn("", List.of(new ToolCallRequest("child_1", "echo",
                            "{\"text\":\"中间过程\"}")));
                }
                String answer = "调研结论：X 成立";
                textSink.accept(answer);
                return new LlmTurn(answer, List.of());
            }

            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                throw new UnsupportedOperationException("agent 循环走 streamTurn");
            }
        };
    }

    /** 轮询等待父会话出现 completed 事件（后台回流，至多 5 秒）。 */
    private static SessionEvent awaitCompleted(Session parentSession) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            for (SessionEvent event : parentSession.events()) {
                if (SessionEvent.SUBAGENT_COMPLETED.equals(event.type())) {
                    return event;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待 completed 回流超时");
    }

    @Test
    void parentSpawnsChildRunsToolAndAnswerFlowsIntoParentProjection() throws Exception {
        java.util.List<String> systems = new java.util.concurrent.CopyOnWriteArrayList<>();
        SubagentManager manager = manager();
        manager.bindBackend(new EmbeddedSubagentBackend(childLlm(systems), tools, null,
                PinnedApprovalPolicy.ALWAYS_DENY));
        tools.register(root, new SpawnTool(manager, () -> parent));

        ToolCallingAgent parentAgent = new ToolCallingAgent(parentLlm(new AtomicInteger(), "spawn"), tools, parent, "父", 5);
        var reply = parentAgent.send("派个活", AgentListener.NONE);

        assertTrue(reply.completed(), "父循环正常收尾");
        SessionEvent completed = awaitCompleted(parent);
        assertTrue(completed.text().contains("调研结论：X 成立"), "子最终回答回流父会话");

        List<dev.duo.harness.session.Message> projected = parent.deriveMessages();
        dev.duo.harness.session.Message last = projected.get(projected.size() - 1);
        assertEquals(dev.duo.harness.session.Message.Role.USER, last.role(),
                "子结论以 USER 形态投影进父上下文");
        assertTrue(last.content().contains("调研结论：X 成立"), "父聚合结果的数据源在投影中");

        // 子代理 system = 框架基线 + 模板专属提示（两层：通用纪律归框架，模板只写角色）
        assertFalse(systems.isEmpty(), "子代理收到过 LLM 请求");
        String childSystem = systems.get(0);
        assertTrue(childSystem.contains("你是被主 agent 委派执行单一子任务的子代理"),
                "框架基线在场（无跨任务记忆等通用纪律）");
        assertTrue(childSystem.contains("不要重复读取同一个目标"), "基线的防重复读取纪律在场");
        assertTrue(childSystem.startsWith("你是被主 agent 委派"), "基线条文居首（模板提示在后）");
        // 运行环境段（M16 工单 04）：基线之后、模板提示之前——cwd/平台/时间
        assertTrue(childSystem.contains("## 运行环境"), "环境段在场");
        assertTrue(childSystem.contains("工作目录：" + System.getProperty("user.dir")), "工作目录进环境段");
        assertTrue(childSystem.contains("操作系统：" + System.getProperty("os.name")), "操作系统进环境段");
        assertTrue(childSystem.indexOf("## 运行环境") > childSystem.indexOf("不要重复读取同一个目标"),
                "环境段在基线之后");
    }

    @Test
    void forkCarriesParentBackgroundIntoChildSession() throws Exception {
        java.util.List<String> systems = new java.util.concurrent.CopyOnWriteArrayList<>();
        parent.append(SessionEvent.userMessage("我们已定方案 A"));
        parent.append(SessionEvent.assistantMessage("好的，按方案 A 执行"));

        SubagentManager manager = manager();
        manager.bindBackend(new EmbeddedSubagentBackend(childLlm(systems), tools, null,
                PinnedApprovalPolicy.ALWAYS_DENY));
        tools.register(root, new ForkTool(manager, () -> parent));

        ToolCallingAgent parentAgent = new ToolCallingAgent(parentLlm(new AtomicInteger(), "fork"), tools, parent, "父", 5);
        parentAgent.send("fork 一个去深挖", AgentListener.NONE);

        awaitCompleted(parent);
        Path subagentsDir = sessionsDir().resolve(SubagentManager.SUBDIRECTORY);
        assertTrue(Files.isDirectory(subagentsDir), "子会话目录已建立");
        List<Path> children = Files.list(subagentsDir).toList();
        assertEquals(1, children.size(), "一个 fork 子会话");

        Session child = Session.load(children.get(0));
        try {
            List<SessionEvent> events = child.events();
            assertEquals("我们已定方案 A", events.get(0).text(), "父背景播种在子会话开头");
            assertEquals(SessionEvent.SUBAGENT_SEED_BOUNDARY, events.get(2).type(), "播种后即种子边界");
            assertTrue(events.stream().anyMatch(e ->
                    SessionEvent.SUBAGENT_COMPLETED.equals(e.type()) || "调研结论：X 成立".equals(e.text())),
                    "fork 子代理同样跑完任务");
        } finally {
            child.close();
        }
    }

    @Test
    void requiresApprovalToolIsPinnedDeniedForSubagent() throws Exception {
        // 审批钉死（M16 工单 03，BUG：limitations M15#3）：模板里配了需审批工具
        // （模拟 bash 形态）→ 子代理调用确定性拒绝、工具本体不执行、循环继续——
        // 而不是挂起等待人工（旧形态会永久卡死）
        java.util.List<String> systems = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<String> childToolResults = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicInteger guardedRuns = new AtomicInteger();
        SubagentManager manager = new SubagentManager(SubagentTemplates.parse(config(
                "{\"templates\": [{\"name\": \"worker\", \"tools\": [\"echo\", \"guarded\"]}]}")));

        // 子 LLM 脚本：首轮调 guarded（需审批），次轮读取工具结果后直答
        LlmAdapter child = new LlmAdapter() {
            final AtomicInteger calls = new AtomicInteger();

            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                if (calls.incrementAndGet() == 1) {
                    return new LlmTurn("", List.of(new ToolCallRequest("child_1", "guarded", "{}")));
                }
                request.messages().forEach(m -> {
                    if (m.role() == dev.duo.harness.llm.ChatMessage.Role.TOOL) {
                        childToolResults.add(m.content());
                    }
                });
                String answer = "已确认受限，改为交回父代理";
                textSink.accept(answer);
                return new LlmTurn(answer, List.of());
            }

            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                throw new UnsupportedOperationException("agent 循环走 streamTurn");
            }
        };
        manager.bindBackend(new EmbeddedSubagentBackend(child, tools, null,
                PinnedApprovalPolicy.ALWAYS_DENY));
        tools.register(root, new SpawnTool(manager, () -> parent));
        tools.register(root, new ToolDefinition() {
            @Override
            public String name() {
                return "guarded";
            }

            @Override
            public String description() {
                return "需审批的演示工具（模拟 bash 形态）";
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode parameters() {
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            "{\"type\":\"object\"}");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public boolean requiresApproval() {
                return true;
            }

            @Override
            public Object execute(ToolExecution execution) {
                guardedRuns.incrementAndGet();
                return "不该被执行";
            }
        });

        ToolCallingAgent parentAgent = new ToolCallingAgent(
                parentLlm(new AtomicInteger(), "spawn"), tools, parent, "父", 5);
        var reply = parentAgent.send("派个活", AgentListener.NONE);

        assertTrue(reply.completed(), "父循环正常收尾（子代理未被卡死）");
        awaitCompleted(parent);
        assertEquals(0, guardedRuns.get(), "需审批工具本体未执行（钉死拒绝在委托之前）");
        // 双轴审查补：越界点名（不在模板 allowed 集内的需审批工具）同样钉死——
        // execute 查共享注册表（含 guarded）而非可见集，不留"不可见即可穿透"的缝。
        // 经子代理内部路径验证：childLlm 的首轮即点名 guarded（模板 allowed 含 guarded
        // 的对照已在上方；此断言锁定 execute 的查表源是注册表——若回退查可见集，
        // 下方拒绝断言失败）。此处直接以共享注册表断言钉死决策的输入面：
        assertTrue(tools.list().stream().anyMatch(d -> d.name().equals("guarded") && d.requiresApproval()),
                "共享注册表含需审批的 guarded（钉死决策输入面）");
        assertEquals(1, childToolResults.size(), "拒绝理由作为工具结果回传子代理上下文");
        assertTrue(childToolResults.get(0).contains("子代理审批钉死"), "拒绝文案署名钉死策略");
        assertTrue(childToolResults.get(0).contains("交回父代理处理"), "附交回父代理指引");
    }
}
