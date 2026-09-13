package dev.duo.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;
import dev.duo.harness.tools.Answerer;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.InteractionPlugin;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * exit_plan_mode 工具用例（交互 seam 装配）：批准写 exited 事件并触发回调、
 * 打回结果携带反馈（不退模式）、fail-closed 保持计划模式（对齐 DSH）。
 */
class ExitPlanModeToolTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ExitPlanModeToolTest —— 计划呈交：批准写事件并回调、"
                + "打回携带反馈不退模式、fail-closed 保持计划模式（3 用例） ===");
    }

    interface ToolsView {

        ToolsService tools();
    }

    interface AnswersView {

        InteractionService answers();
    }

    @TempDir
    Path tempDir;

    private Context root;

    @AfterEach
    void tearDown() {
        if (root != null) {
            root.dispose();
        }
    }

    /** 装配：交互插件 + 工具域 + exit_plan_mode（answerer 由用例注册或省略）。 */
    private ToolsService assemble(Answerer answerer, Session session,
                                  AtomicInteger approvals) throws IOException {
        root = Context.root();
        root.plugin(new InteractionPlugin(), null).awaitStartup();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        ToolsService tools = root.as(ToolsView.class).tools();
        InteractionService answers = root.as(AnswersView.class).answers();
        if (answerer != null) {
            answers.register(root, answerer);
        }
        tools.register(root, new ExitPlanModeTool(answers, () -> session, approvals::incrementAndGet));
        return tools;
    }

    private Session newSession() throws IOException {
        return Session.create(tempDir.resolve("sessions"));
    }

    private static JsonNode args(String plan) {
        try {
            return new ObjectMapper().readTree("{\"plan\": \"" + plan + "\"}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void approvalWritesExitedEventAndRunsCallback() throws IOException {
        Session session = newSession();
        session.append(PlanMode.enteredEvent());
        AtomicInteger approvals = new AtomicInteger();
        ToolsService tools = assemble(
                request -> InteractionAnswer.answered(List.of(ExitPlanModeTool.APPROVE_OPTION), "console"),
                session, approvals);

        ToolResult result = tools.execute(ExitPlanModeTool.NAME, args("1. 加文件 2. 改文档"));

        assertFalse(result.isError());
        String text = String.valueOf(result.value());
        assertTrue(text.contains("已获批准"), text);
        assertEquals(1, approvals.get(), "批准回调触发（装配侧摘除指导片段）");
        assertTrue(PlanMode.isActive(session) == false, "exited 事件落盘，模式退出");
        assertEquals("exited", session.events().get(1).text(), "plan/mode exited 事件落盘");
    }

    @Test
    void reworkCarriesFeedbackAndStaysInPlanMode() throws IOException {
        Session session = newSession();
        session.append(PlanMode.enteredEvent());
        AtomicInteger approvals = new AtomicInteger();
        ToolsService tools = assemble(
                request -> InteractionAnswer.answered(List.of("第一步换成加测试"), "console"),
                session, approvals);

        ToolResult result = tools.execute(ExitPlanModeTool.NAME, args("计划"));

        assertFalse(result.isError(), "打回是继续计划的指令，非错误形态");
        String text = String.valueOf(result.value());
        assertTrue(text.contains("继续修改计划"), text);
        assertTrue(text.contains("第一步换成加测试"), "反馈原文进结果: " + text);
        assertEquals(0, approvals.get(), "打回不触发批准回调");
        assertTrue(PlanMode.isActive(session), "打回后保持计划模式");
    }

    @Test
    void noAnswererFailsClosedAndStaysInPlanMode() throws IOException {
        Session session = newSession();
        session.append(PlanMode.enteredEvent());
        AtomicInteger approvals = new AtomicInteger();
        ToolsService tools = assemble(null, session, approvals);

        ToolResult result = tools.execute(ExitPlanModeTool.NAME, args("计划"));

        assertFalse(result.isError(), "fail-closed 为正常结果形态（计划不批准），非系统错误");
        String text = String.valueOf(result.value());
        assertTrue(text.contains("fail-closed"), text);
        assertTrue(text.contains("未获批准"), text);
        assertEquals(0, approvals.get());
        assertTrue(PlanMode.isActive(session), "fail-closed 保持计划模式（对齐 DSH）");
    }
}
