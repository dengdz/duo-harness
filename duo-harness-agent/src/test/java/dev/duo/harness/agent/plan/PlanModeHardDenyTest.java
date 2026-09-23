package dev.duo.harness.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.internal.ToolCallingAgent;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsService;
import dev.duo.harness.tools.fs.ReadOnlyBashDetector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划模式硬禁用例（M24 工单 04，ADR-0026 决策三）：plan 态注入收缩（非白名单
 * 工具定义不进请求——模型不可见）、pre-execute deny 兜底（理由回模型成对落日志
 * 无悬置态）、bash 参数级裁决（只读放行/写命令拒/判定器缺席 fail-closed）、
 * 批准后全量恢复（含续接恢复激活态场景）。
 */
class PlanModeHardDenyTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：PlanModeHardDenyTest —— 计划模式硬禁：注入收缩、deny 兜底、"
                + "bash 参数级裁决、批准恢复（8 用例） ===");
    }

    @TempDir
    Path tempDir;

    private Session newSession() throws IOException {
        return Session.create(tempDir.resolve("sessions"));
    }

    /** 可编程工具域：注册定义清单 + 记录 execute 调用 + 固定返回。 */
    private static class ScriptedTools implements ToolsService {

        final List<ToolDefinition> definitions;
        final List<String> executed = new CopyOnWriteArrayList<>();

        ScriptedTools(List<ToolDefinition> definitions) {
            this.definitions = definitions;
        }

        @Override
        public Disposable register(Context registrant, ToolDefinition definition) {
            throw new UnsupportedOperationException("测试不走 register");
        }

        @Override
        public Disposable guard(Context registrant, dev.duo.harness.tools.GuardCheck check) {
            throw new UnsupportedOperationException("测试不走 guard");
        }

        @Override
        public List<ToolDefinition> list() {
            return definitions;
        }

        @Override
        public ToolResult execute(String toolName, JsonNode args) {
            executed.add(toolName);
            return ToolResult.of(toolName + "-done");
        }
    }

    /** 测试工具定义：名字可配，并发安全。 */
    private ToolDefinition tool(String name) {
        return new ToolDefinition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + " 测试工具";
            }

            @Override
            public JsonNode parameters() {
                return JsonNodeFactory.instance.objectNode().put("type", "object");
            }

            @Override
            public boolean isConcurrencySafe(JsonNode args) {
                return true;
            }

            @Override
            public Object execute(ToolExecution execution) {
                return name + "-done";
            }
        };
    }

    /** 两轮 mock：首轮发起指定工具调用，次轮直答收口；两轮请求全部捕获。 */
    private LlmAdapter twoTurnAdapter(List<ToolCallRequest> firstTurnCalls,
                                      List<ChatRequest> captured) {
        AtomicInteger turn = new AtomicInteger();
        return new LlmAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                throw new UnsupportedOperationException("测试主循环走 streamTurn");
            }

            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                captured.add(request);
                if (turn.getAndIncrement() == 0) {
                    return new LlmTurn("我先调工具", List.copyOf(firstTurnCalls));
                }
                textSink.accept("收口");
                return new LlmTurn("收口", List.of());
            }
        };
    }

    private static AgentListener none() {
        return AgentListener.NONE;
    }

    @Test
    void planModeShrinksInjectedToolSpecs() throws Exception {
        // 工单 04 注入收缩：plan 态请求里非白名单工具定义不出现（模型不可见）
        Session session = newSession();
        session.append(PlanMode.enteredEvent());
        ScriptedTools tools = new ScriptedTools(List.of(
                tool("read"), tool("glob"), tool("write"), tool("bash"), tool("edit")));
        List<ChatRequest> captured = new ArrayList<>();
        ToolCallingAgent agent = new ToolCallingAgent(
                twoTurnAdapter(List.of(), captured), tools, session,
                new dev.duo.harness.agent.prompt.PromptRegistry("测试提示"), 10, 1,
                null, null, null, false, null, null);

        agent.send("帮我看看", none());

        List<String> specNames = captured.get(0).tools().stream()
                .map(dev.duo.harness.llm.ToolSpec::name).toList();
        assertFalse(specNames.contains("write"), "写工具 plan 态不可见: " + specNames);
        assertFalse(specNames.contains("bash"), "bash plan 态不可见: " + specNames);
        assertFalse(specNames.contains("edit"), "edit plan 态不可见: " + specNames);
        assertTrue(specNames.contains("read"), "只读工具在白名单: " + specNames);
        assertTrue(specNames.contains("glob"), "只读工具在白名单: " + specNames);
        assertFalse(captured.get(0).tools().isEmpty(), "白名单工具仍注入");
        session.close();
    }

    @Test
    void approvalExitRestoresFullInjection() throws Exception {
        // 工单 04 批准闭环：exited 事件落盘后工具全量恢复（isActive 驱动，无额外恢复代码）
        Session session = newSession();
        session.append(PlanMode.enteredEvent());
        session.append(PlanMode.exitedEvent());
        ScriptedTools tools = new ScriptedTools(List.of(tool("read"), tool("write")));
        List<ChatRequest> captured = new ArrayList<>();
        ToolCallingAgent agent = new ToolCallingAgent(
                twoTurnAdapter(List.of(), captured), tools, session,
                new dev.duo.harness.agent.prompt.PromptRegistry("测试提示"), 10, 1,
                null, null, null, false, null, null);

        agent.send("开工", none());

        List<String> specNames = captured.get(0).tools().stream()
                .map(dev.duo.harness.llm.ToolSpec::name).toList();
        assertTrue(specNames.contains("write"), "批准后写工具恢复注入: " + specNames);
        assertTrue(specNames.contains("read"), "批准后只读工具照常: " + specNames);
        assertFalse(PlanMode.isActive(session), "exited 后 plan 态结束");
        session.close();
    }

    @Test
    void planDeniesWriteWithReasonToModel() throws Exception {
        // 工单 04 deny 兜底：plan 态调 write——理由经 tool/result 回模型（成对落日志
        // 无悬置态），工具实现不触达
        Session session = newSession();
        session.append(PlanMode.enteredEvent());
        ScriptedTools tools = new ScriptedTools(List.of(tool("write")));
        List<ChatRequest> captured = new ArrayList<>();
        ToolCallingAgent agent = new ToolCallingAgent(
                twoTurnAdapter(List.of(new ToolCallRequest("c1", "write", "{\"path\":\"x\",\"content\":\"y\"}")),
                        captured),
                tools, session,
                new dev.duo.harness.agent.prompt.PromptRegistry("测试提示"), 10, 1,
                null, null, null, false, null, null);

        AgentReply reply = agent.send("帮我写文件", none());

        assertTrue(reply.completed(), "deny 收敛为正常收口不留悬置态");
        assertTrue(tools.executed.isEmpty(), "deny 路径不触达工具实现: " + tools.executed);
        List<String> resultTexts = session.events().stream()
                .filter(e -> SessionEvent.TOOL_RESULT.equals(e.type()))
                .map(SessionEvent::text).toList();
        assertEquals(1, resultTexts.size());
        assertTrue(resultTexts.get(0).contains("[plan] 计划模式下工具 write 不可用"),
                "deny 理由回模型: " + resultTexts);
        session.close();
    }

    @Test
    void bashReadonlyCommandIsAllowedUnderPlan() throws Exception {
        // ADR「只读 bash 策略表判定通过者在白名单」：plan 态 bash 只读命令（ls）放行进执行域
        Session session = newSession();
        session.append(PlanMode.enteredEvent());
        ScriptedTools tools = new ScriptedTools(List.of(tool("bash")));
        ToolCallingAgent agent = new ToolCallingAgent(
                twoTurnAdapter(List.of(new ToolCallRequest("c1", "bash", "{\"command\":\"ls\"}")),
                        new ArrayList<>()),
                tools, session,
                new dev.duo.harness.agent.prompt.PromptRegistry("测试提示"), 10, 4,
                null, null, null, false, null, new ReadOnlyBashDetector(tempDir));

        agent.send("看看目录", none());

        assertTrue(tools.executed.contains("bash"), "只读 bash（ls）plan 态放行: " + tools.executed);
        session.close();
    }

    @Test
    void bashWriteCommandIsDeniedUnderPlan() throws Exception {
        // plan 态 bash 写命令（rm）deny——理由经 tool/result 回模型
        Session session = newSession();
        session.append(PlanMode.enteredEvent());
        ScriptedTools tools = new ScriptedTools(List.of(tool("bash")));
        ToolCallingAgent agent = new ToolCallingAgent(
                twoTurnAdapter(List.of(new ToolCallRequest("c1", "bash", "{\"command\":\"rm -rf /tmp/x\"}")),
                        new ArrayList<>()),
                tools, session,
                new dev.duo.harness.agent.prompt.PromptRegistry("测试提示"), 10, 1,
                null, null, null, false, null, new ReadOnlyBashDetector(tempDir));

        agent.send("清目录", none());

        assertTrue(tools.executed.isEmpty(), "写命令不触达执行域: " + tools.executed);
        assertTrue(session.events().stream()
                        .filter(e -> SessionEvent.TOOL_RESULT.equals(e.type()))
                        .map(SessionEvent::text)
                        .anyMatch(t -> t.contains("计划模式下工具 bash 不可用")),
                "写命令被 deny 且理由回模型");
        session.close();
    }

    @Test
    void bashWithoutDetectorFailsClosedUnderPlan() throws Exception {
        // 判定器缺席（纯对话装配）：plan 态 bash 连只读命令也 fail-closed 拒
        Session session = newSession();
        session.append(PlanMode.enteredEvent());
        ScriptedTools tools = new ScriptedTools(List.of(tool("bash")));
        ToolCallingAgent agent = new ToolCallingAgent(
                twoTurnAdapter(List.of(new ToolCallRequest("c1", "bash", "{\"command\":\"ls\"}")),
                        new ArrayList<>()),
                tools, session,
                new dev.duo.harness.agent.prompt.PromptRegistry("测试提示"), 10, 1,
                null, null, null, false, null, null);

        agent.send("看看目录", none());

        assertTrue(tools.executed.isEmpty(), "判定器缺席 fail-closed: " + tools.executed);
        assertTrue(PlanMode.denyReason(session, "bash", "{\"command\":\"ls\"}", null) != null,
                "denyReason 显式可查");
        session.close();
    }

    @Test
    void concurrentGroupCallsAreDeniedUnderPlan() throws Exception {
        // 工单 04 审查补测：并发执行点（池内 planDenyOrExecute）同样被 guard——
        // 两个并发安全调用在 plan 态双 deny，理由成对落日志
        Session session = newSession();
        session.append(PlanMode.enteredEvent());
        ScriptedTools tools = new ScriptedTools(List.of(tool("write")));
        ToolCallingAgent agent = new ToolCallingAgent(
                twoTurnAdapter(List.of(
                        new ToolCallRequest("c1", "write", "{\"path\":\"a\"}"),
                        new ToolCallRequest("c2", "write", "{\"path\":\"b\"}")),
                        new ArrayList<>()),
                tools, session,
                new dev.duo.harness.agent.prompt.PromptRegistry("测试提示"), 10, 4,
                null, null, null, false, null, null);

        agent.send("写两个文件", none());

        assertTrue(tools.executed.isEmpty(), "并发组 deny 不触达执行域: " + tools.executed);
        long denied = session.events().stream()
                .filter(e -> SessionEvent.TOOL_RESULT.equals(e.type()))
                .filter(e -> e.text().contains("计划模式下工具 write 不可用"))
                .count();
        assertEquals(2, denied, "两个并发调用各自收到 deny 理由");
        session.close();
    }

    @Test
    void whitelistMembersReachExecutionUnderPlan() throws Exception {
        // 工单 04 白名单成员：plan 态照常进执行域（read 探索 + exit_plan_mode 批准闭环）
        Session session = newSession();
        session.append(PlanMode.enteredEvent());
        ScriptedTools tools = new ScriptedTools(List.of(tool("read"), tool("exit_plan_mode")));
        List<ChatRequest> captured = new ArrayList<>();
        ToolCallingAgent agent = new ToolCallingAgent(
                twoTurnAdapter(List.of(new ToolCallRequest("c1", "read", "{}")),
                        captured),
                tools, session,
                new dev.duo.harness.agent.prompt.PromptRegistry("测试提示"), 10, 1,
                null, null, null, false, null, null);

        agent.send("先探索", none());

        assertTrue(tools.executed.contains("read"), "白名单工具 plan 态照常执行: " + tools.executed);
        assertNull(PlanMode.denyReason(session, "exit_plan_mode", "{}", null),
                "exit_plan_mode 恒在（批准闭环不动）");
        session.close();
    }
}
