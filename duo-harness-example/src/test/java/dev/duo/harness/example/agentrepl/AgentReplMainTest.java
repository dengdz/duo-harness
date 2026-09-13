package dev.duo.harness.example.agentrepl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * agent 演示冒烟（M5）：Function Calling 端到端——mock LLM 要求调工具 →
 * 真 ToolsService 执行 → 结果回填 → 二轮直答；迭代上限触发。
 */
class AgentReplMainTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：AgentReplMainTest —— agent 演示冒烟：Function Calling 闭环"
                + "（调工具 → 治理链 → 回填 → 直答）+ 迭代上限（1 用例） ===");
    }

    @Test
    void toolLoopExecutesViaPipelineAndAnswers(@TempDir Path sessionsDir) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);

        Context root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
                interface ToolsViewX { ToolsService tools(); }
        ToolsService tools = root.as(ToolsViewX.class).tools();
        tools.register(root, echoDef());

        // mock LLM 脚本：第一轮要求调 echo → 第二轮直答总结
        LlmAdapter scriptLlm = new LlmAdapter() {
            int calls = 0;

            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                calls++;
                if (calls == 1) {
                    return new LlmTurn("", List.of(
                            new ToolCallRequest("call_1", "echo", "{\"text\":\"你好\"}")));
                }
                String last = request.messages().get(request.messages().size() - 1).content();
                String answer = "工具说: " + last;
                textSink.accept(answer);   // 模拟流式直答输出
                return new LlmTurn(answer, List.of());
            }

            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                throw new UnsupportedOperationException("agent 循环走 streamTurn");
            }
        };

        Session session = Session.create(sessionsDir);
        var agent = new dev.duo.harness.agent.internal.ToolCallingAgent(scriptLlm, tools, session, "你是助手", 5);

        var reply = agent.send("打个招呼", new dev.duo.harness.agent.AgentListener() {
            @Override
            public void onToolCall(String toolName, String argumentsJson) {
                out.println("  [调工具] " + toolName);
            }

            @Override
            public void onToolResult(String toolName, String resultText, boolean isError) {
                out.println("  [工具结果] " + resultText);
            }
        });

        assertTrue(reply.completed());
        assertEquals(1, reply.toolInvocations().size(), "一次工具调用");
        assertEquals("echo", reply.toolInvocations().get(0).tool());
        assertEquals("工具说: echo:你好", reply.finalText(), "工具结果回填后 LLM 总结");
        // 会话日志含完整过程：user + tool/call + tool/result + assistant
        assertEquals(4, session.events().size());
        assertEquals(SessionEvent.TOOL_CALL, session.events().get(1).type());
        assertEquals(SessionEvent.TOOL_RESULT, session.events().get(2).type());
        assertEquals(SessionEvent.ASSISTANT_MESSAGE, session.events().get(3).type());
        root.dispose();
    }

    @Test
    void iterationLimitStopsRunawayLoop(@TempDir Path sessionsDir) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);

        Context root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
                interface ToolsViewX { ToolsService tools(); }
        ToolsService tools = root.as(ToolsViewX.class).tools();
        tools.register(root, echoDef());

        // mock LLM：永远要求调工具（异常任务）
        LlmAdapter loopLlm = new LlmAdapter() {
            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                return new LlmTurn("", List.of(new ToolCallRequest("call", "echo", "{}")));
            }

            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                throw new UnsupportedOperationException("循环路径走 streamTurn");
            }
        };
        Session session = Session.create(sessionsDir);
        var agent = new dev.duo.harness.agent.internal.ToolCallingAgent(loopLlm, tools, session, "你是助手", 3);

        var reply = agent.send("无限循环任务", AgentListener.NONE);

        assertEquals(false, reply.completed(), "上限触发应为异常终止");
        assertEquals(3, reply.toolInvocations().size(), "恰好执行 3 轮工具调用");
        assertTrue(reply.finalText().contains("已达最大迭代轮数（3）"), reply.finalText());
        root.dispose();
    }

    @Test
    void skillInvocationPrefixInjectsInstructions(@TempDir Path fixture) throws IOException {
        // 工单 03：/技能名 前缀直调——指令全文前缀注入（用户直调路）
        Files.createDirectories(fixture.resolve("release-notes"));
        Files.writeString(fixture.resolve("release-notes").resolve("SKILL.md"),
                "---\nname: release-notes\ndescription: 生成发布说明\n---\n请按仓库规范撰写发布说明。");
        dev.duo.harness.agent.SkillRegistry registry =
                dev.duo.harness.agent.SkillRegistry.scan(List.of(fixture), java.util.Set.of());

        // 命中：指令全文 + 用户输入
        assertEquals("请按仓库规范撰写发布说明。\n\n用户输入：0.3.0",
                AgentReplMain.resolveSkillInvocation("/release-notes 0.3.0", registry));
        // 命中：无其余输入 → 仅指令全文
        assertEquals("请按仓库规范撰写发布说明。",
                AgentReplMain.resolveSkillInvocation("/release-notes", registry));
        // 未知名 → null（REPL 提示未知命令）
        assertNull(AgentReplMain.resolveSkillInvocation("/不存在", registry));
        // 非斜杠输入原样透传
        assertEquals("普通问题", AgentReplMain.resolveSkillInvocation("普通问题", registry));
    }

    @Test
    void agentDemoYmlBootsCleanly() throws Exception {
        // 防回归：run() 需要 ~/.duo/config.yml 的真实 key，测试覆盖不到 yml 装载——
        // BUG（工单05 验收发现）：repeat-reminder 行缺 config 块，Boot 严格绑定整树点名失败
        Path yml = Path.of(AgentReplMain.class.getResource("/agent-demo.yml").toURI());
        Context root = dev.duo.harness.core.api.boot.Boot.from(yml);
        root.dispose();
    }

    private static ToolDefinition echoDef() {
        return new ToolDefinition() {
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
                return JsonNodeFactory.instance.objectNode().put("type", "object");
            }

            @Override
            public Object execute(ToolExecution execution) {
                return "echo:" + execution.args().path("text").asText("");
            }
        };
    }
}
