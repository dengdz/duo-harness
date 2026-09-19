package dev.duo.harness.example;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.AuditingAnswerer;
import dev.duo.harness.agent.internal.ToolCallingAgent;
import dev.duo.harness.agent.prompt.PromptPlugin;
import dev.duo.harness.agent.prompt.PromptRegistry;
import dev.duo.harness.cli.ConsoleAnswerer;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionPlugin;
import dev.duo.harness.tools.InteractionRequest;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import dev.duo.harness.web.WebAnswerer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双呈现位亲和路由装配用例（M19 工单 05，缝 3 双开，ADR-0020 决策 7）：
 * 复刻 agent-demo 的回答者注册序（web 在前、cli 在后），CLI 发起的需审批工具
 * 调用路由给**终端**回答者作答（"谁发起谁作答"）——M12-02 事故（审批跳 Web
 * 卡片、终端零提示）在装配语义下的销账验证。Web answerer 以短兜底超时在场：
 * 若路由错误地落到它，将超时 fail-closed、工具被拒，断言即失败。
 */
class DualPresenterAffinityTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：DualPresenterAffinityTest —— 双呈现位亲和路由（cli 发起审批 → "
                + "终端作答而非 web 卡片，注册序 web 前 cli 后）（1 用例） ===");
    }

    interface ToolsView {

        ToolsService tools();
    }

    interface AnswersView {

        InteractionService answers();
    }

    @TempDir
    Path tempDir;

    @Test
    void cliInitiatedApprovalAnswersAtConsoleNotWeb() throws Exception {
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            root.plugin(new PromptPlugin(), JsonNodeFactory.instance.objectNode()
                    .put("systemPrompt", "测试提示")).awaitStartup();
            root.plugin(new InteractionPlugin(), null).awaitStartup();
            root.plugin(new dev.duo.harness.agent.commands.CommandsPlugin(),
                    JsonNodeFactory.instance.objectNode()).awaitStartup();
            // fs 工具族（workspace 服务）+ 档位审批：ask 落回答者瀑布
            root.plugin(new dev.duo.harness.tools.fs.FsToolsPlugin(),
                    JsonNodeFactory.instance.objectNode().put("mode", "read-only")
                            .put("root", tempDir.toAbsolutePath().toString())).awaitStartup();
            root.plugin(new dev.duo.harness.tools.fs.WorkspaceApprovalPlugin(), null).awaitStartup();

            // 需审批的测试工具（双开下无论发起方是谁都走 ask）
            AtomicInteger approvals = new AtomicInteger();
            root.as(ToolsView.class).tools().register(root, new ToolDefinition() {
                @Override
                public String name() {
                    return "guarded_write";
                }

                @Override
                public String description() {
                    return "需审批的写入";
                }

                @Override
                public com.fasterxml.jackson.databind.JsonNode parameters() {
                    return JsonNodeFactory.instance.objectNode().put("type", "object");
                }

                @Override
                public boolean requiresApproval() {
                    return true;
                }

                @Override
                public Object execute(ToolExecution execution) {
                    approvals.incrementAndGet();
                    return "written";
                }
            });

            // 回答者注册序 = agent-demo 行序：web 在前（短兜底超时——路由错即快速失败）、
            // cli 在后（AuditingAnswerer 装饰桥 + 脚本终端）
            WebAnswerer webAnswerer = new WebAnswerer(600);
            root.as(AnswersView.class).answers().register(root, webAnswerer);
            ByteArrayOutputStream terminal = new ByteArrayOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8));
            Session session = Session.create(tempDir.resolve("sessions"));
            root.as(AnswersView.class).answers().register(root, new AuditingAnswerer(
                    () -> session, new ConsoleAnswerer(in,
                    new PrintStream(terminal, true, StandardCharsets.UTF_8))));

            // cli 呈现位的执行链（presenterId=cli 随工具执行进管线）：
            // mock LLM 首轮发起需审批调用，审批放行后次轮直答
            AtomicInteger turn = new AtomicInteger();
            LlmAdapter llm = new LlmAdapter() {
                @Override
                public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public LlmTurn streamTurn(ChatRequest request,
                                          java.util.function.Consumer<String> textSink) {
                    if (turn.incrementAndGet() == 1) {
                        return new LlmTurn("", List.of(
                                new ToolCallRequest("call_1", "guarded_write", "{}")));
                    }
                    textSink.accept("已执行");
                    return new LlmTurn("已执行", List.of());
                }
            };
            ToolCallingAgent agent = new ToolCallingAgent(llm,
                    root.as(ToolsView.class).tools(), session,
                    new PromptRegistry("测试"), 5, 5, null, ChatAgent.PRESENTER_CLI);

            var reply = agent.send("写个文件", dev.duo.harness.agent.AgentListener.NONE);

            String out = terminal.toString(StandardCharsets.UTF_8);
            assertTrue(out.contains("[待审批]"), "审批呈现给终端（亲和路由命中 console，"
                    + "而非 Web 卡片）: " + out);
            assertTrue(reply.completed(), "审批放行、工具执行、循环完成: " + reply.finalText());
            // 工具真实执行经会话事件验证（send 用 NONE 监听器，终端不打工具行）
            assertTrue(session.events().stream().anyMatch(e ->
                            dev.duo.harness.session.SessionEvent.TOOL_RESULT.equals(e.type())
                                    && "written".equals(e.text())),
                    "y 放行后工具真实执行: " + out);

            // 审计留痕：决定署名 console（若路由到 web 且超时 fail-closed，此处署名不符）
            var decided = session.events().stream()
                    .filter(e -> dev.duo.harness.session.SessionEvent.APPROVAL_DECIDED
                            .equals(e.type()))
                    .findFirst().orElseThrow();
            assertTrue(decided.text().contains("console"),
                    "决定由终端回答者作答并署名: " + decided.text());
            assertEquals(1, approvals.get(), "工具本体恰执行一次");
            session.close();
        } finally {
            root.dispose();
        }
    }
}
