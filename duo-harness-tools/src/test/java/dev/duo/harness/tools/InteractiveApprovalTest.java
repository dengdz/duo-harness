package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.core.api.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * interactive 审批插件用例（工具服务级装配）：ask 委托交互 seam 由在场回答者作答、
 * 回答者放行则执行本体运行、拒绝转错误结果（含回答者来源）、回答者缺位 fail-closed、
 * answers 服务缺位时插件 PENDING → 工具域退回"未配置即拒"（ADR-0008）。
 */
class InteractiveApprovalTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：InteractiveApprovalTest —— interactive 审批：ask 委托回答者作答、"
                + "放行执行/拒绝转错误、回答者缺位 fail-closed、服务缺位退回未配置即拒（4 用例） ===");
    }

    /** 服务视图接口（方法名即服务名）。 */
    interface ToolsView {

        ToolsService tools();
    }

    interface AnswersView {

        InteractionService answers();
    }

    private Context root;

    @AfterEach
    void tearDown() {
        if (root != null) {
            root.dispose();
        }
    }

    /** 装配：交互插件（恒挂载）+ 交互审批 + 工具域；answerer 为 null = 服务在场但无回答者。 */
    private ToolsService assemble(Answerer answerer) {
        root = Context.root();
        root.plugin(new InteractionPlugin(), null).awaitStartup();
        root.plugin(new InteractiveApprovalPlugin(), null).awaitStartup();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        if (answerer != null) {
            root.as(AnswersView.class).answers().register(root, answerer);
        }
        return root.as(ToolsView.class).tools();
    }

    /** 不带交互插件的装配（answers 服务缺位 → 交互审批插件 PENDING）。
     *  注意：PENDING 的插件 awaitStartup 会永久阻塞，此处刻意不等待——
     *  正是要验证"依赖缺席时审批服务缺位、工具域退回未配置即拒"。 */
    private ToolsService assembleWithoutAnswers() {
        root = Context.root();
        root.plugin(new InteractiveApprovalPlugin(), null);
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        return root.as(ToolsView.class).tools();
    }

    private static JsonNode config(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 需审批的计数工具（被放行时执行并返回结果）。 */
    private static ToolDefinition gatedEcho(AtomicInteger executions) {
        return new ToolDefinition() {
            @Override
            public String name() {
                return "gated";
            }

            @Override
            public String description() {
                return "需审批的回声工具";
            }

            @Override
            public JsonNode parameters() {
                return JsonNodeFactory.instance.objectNode().put("type", "object");
            }

            @Override
            public boolean requiresApproval() {
                return true;
            }

            @Override
            public String execute(ToolExecution execution) {
                executions.incrementAndGet();
                return "已执行";
            }
        };
    }

    @Test
    void answererAllowRunsToolBody() {
        AtomicInteger executions = new AtomicInteger();
        ToolsService tools = assemble(request -> InteractionAnswer.allow("test-console"));
        tools.register(root, gatedEcho(executions));

        ToolResult result = tools.execute("gated", config("{}"));

        assertFalse(result.isError());
        assertEquals("已执行", result.value(), "回答者放行 → 执行本体运行");
        assertEquals(1, executions.get());
    }

    @Test
    void answererDenyConvergesToErrorResultWithSource() {
        ToolsService tools = assemble(request -> InteractionAnswer.deny("test-console"));
        AtomicInteger executions = new AtomicInteger();
        tools.register(root, gatedEcho(executions));

        ToolResult result = tools.execute("gated", config("{}"));

        assertTrue(result.isError());
        assertTrue(String.valueOf(result.value()).contains("被人拒绝"), String.valueOf(result.value()));
        assertTrue(String.valueOf(result.value()).contains("test-console"),
                "拒绝理由携带回答者来源: " + String.valueOf(result.value()));
        assertEquals(0, executions.get(), "被拒绝的工具本体不执行");
    }

    @Test
    void noAnswererRegisteredFailsClosed() {
        ToolsService tools = assemble(null);
        AtomicInteger executions = new AtomicInteger();
        tools.register(root, gatedEcho(executions));

        ToolResult result = tools.execute("gated", config("{}"));

        assertTrue(result.isError());
        assertTrue(String.valueOf(result.value()).contains("无人应答"), String.valueOf(result.value()));
        assertTrue(String.valueOf(result.value()).contains("fail-closed"), String.valueOf(result.value()));
        assertEquals(0, executions.get());
    }

    @Test
    void answersServiceMissingFallsBackToUnconfiguredDeny() {
        // 交互审批插件 PENDING（inject 缺失）→ approval 服务缺位 → 管线兜底"未配置即拒"
        ToolsService tools = assembleWithoutAnswers();
        AtomicInteger executions = new AtomicInteger();
        tools.register(root, gatedEcho(executions));

        ToolResult result = tools.execute("gated", config("{}"));

        assertTrue(result.isError());
        assertTrue(String.valueOf(result.value()).contains("审批策略未配置"), String.valueOf(result.value()));
        assertEquals(0, executions.get(), "缺回答者时安全兜底拒绝");
    }
}
