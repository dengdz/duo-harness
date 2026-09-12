package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ask_user 提问工具用例（工具服务级装配）：选项回答 / 自由文本 / 多选拼接原样
 * 回填为工具结果；缺 question 收敛为错误结果点名；无人应答 fail-closed 收敛为
 * 错误结果（ADR-0008）。
 */
class AskUserToolTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：AskUserToolTest —— ask_user：选项回答/自由文本/多选回填、"
                + "缺 question 点名、无人应答 fail-closed（5 用例） ===");
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

    /** 装配：交互插件 + 工具域 + ask_user（answerer 由用例注册或省略）。 */
    private ToolsService assemble(Answerer answerer) {
        root = Context.root();
        root.plugin(new InteractionPlugin(), null).awaitStartup();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        ToolsService tools = root.as(ToolsView.class).tools();
        if (answerer != null) {
            root.as(AnswersView.class).answers().register(root, answerer);
        }
        tools.register(root, new AskUserTool(root.as(AnswersView.class).answers()));
        return tools;
    }

    private static JsonNode args(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void optionAnswerIsReturnedAsToolResult() {
        java.util.List<InteractionRequest> seen = new java.util.ArrayList<>();
        ToolsService tools = assemble(request -> {
            seen.add(request);
            return InteractionAnswer.answered(java.util.List.of("方案 A"), "test");
        });

        ToolResult result = tools.execute(AskUserTool.NAME,
                args("{\"question\":\"用哪个方案？\",\"options\":[\"方案 A\",\"方案 B\"]}"));

        assertFalse(result.isError());
        assertEquals("方案 A", result.value(), "用户选择的选项原样回填");
        assertEquals(InteractionRequest.KIND_QUESTION, seen.get(0).kind());
        assertEquals("用哪个方案？", seen.get(0).subject());
        assertEquals(java.util.List.of("方案 A", "方案 B"), seen.get(0).options());
    }

    @Test
    void freeTextAnswerWithoutOptions() {
        ToolsService tools = assemble(request -> InteractionAnswer.answered(java.util.List.of("用数据库存"), "test"));

        ToolResult result = tools.execute(AskUserTool.NAME, args("{\"question\":\"数据存哪？\"}"));

        assertFalse(result.isError());
        assertEquals("用数据库存", result.value(), "自由文本回答原样回填");
    }

    @Test
    void multiSelectAnswersAreJoinedPerLine() {
        ToolsService tools = assemble(request ->
                InteractionAnswer.answered(java.util.List.of("A", "B"), "test"));

        ToolResult result = tools.execute(AskUserTool.NAME,
                args("{\"question\":\"带上哪些？\",\"options\":[\"A\",\"B\",\"C\"],\"multiSelect\":true}"));

        assertFalse(result.isError());
        assertEquals("A\nB", result.value(), "多选答案逐行拼接回填");
    }

    @Test
    void missingQuestionConvergesToErrorResult() {
        ToolsService tools = assemble(request -> InteractionAnswer.allow("test"));

        ToolResult result = tools.execute(AskUserTool.NAME, args("{\"options\":[\"A\"]}"));

        assertTrue(result.isError(), "缺 question 应收敛为错误结果");
        assertTrue(String.valueOf(result.value()).contains("question"), String.valueOf(result.value()));
    }

    @Test
    void noAnswererFailsClosedAsErrorResult() {
        ToolsService tools = assemble(null);

        ToolResult result = tools.execute(AskUserTool.NAME, args("{\"question\":\"在吗？\"}"));

        assertTrue(result.isError());
        assertTrue(String.valueOf(result.value()).contains("无人应答"), String.valueOf(result.value()));
    }
}
