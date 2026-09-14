package dev.duo.harness.agent.presenter;

import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.ContextGovernance;
import dev.duo.harness.agent.PromptRegistry;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.InteractionPlugin;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享装配器用例（M11-01）：呈现位（CLI/Web）共用的装配单点——agent 装配产物
 * 可执行一轮对话（事件落会话）、交互工具查重注册（先到方胜出、二次注册跳过）、
 * LLM 装配的配置装载失败点名。
 */
class PresenterAssemblyTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：PresenterAssemblyTest —— 呈现位共享装配器：执行链装配、"
                + "交互工具查重注册、LLM 配置失败点名（3 用例） ===");
    }

    interface ToolsView {

        ToolsService tools();
    }

    interface AnswersView {

        InteractionService answers();
    }

    @TempDir
    Path tempDir;

    /** 单段直答 mock adapter（装配产物验证用，不经网络）。 */
    private static LlmAdapter scriptedAdapter(String reply) {
        return new LlmAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                onChunk.accept(new ChatChunk(reply));
            }

            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                textSink.accept(reply);
                return new LlmTurn(reply, List.of());
            }
        };
    }

    @Test
    void chatAgentAssemblyExecutesTurnAndLogsSession() throws IOException {
        // 装配产物可用性：治理 + agent 组合执行一轮直答，事件正确落会话
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            ToolsService tools = root.as(ToolsView.class).tools();
            PromptRegistry prompts = new PromptRegistry(null); // 全空落内置缺省提示
            LlmAdapter llm = scriptedAdapter("装配验证");
            ContextGovernance governance = PresenterAssembly.governance(llm);
            Session session = Session.create(tempDir.resolve("sessions"));

            ChatAgent agent = PresenterAssembly.chatAgent(llm, tools, session, prompts, governance);
            agent.send("问", AgentListener.NONE);

            assertEquals(2, session.events().size(), "user/message + assistant/message");
            assertEquals(SessionEvent.ASSISTANT_MESSAGE, session.events().get(1).type());
            assertEquals("装配验证", session.events().get(1).text());
            session.close();
        } finally {
            root.dispose();
        }
    }

    @Test
    void interactionToolsRegisterOncePerName() {
        // 查重先到先得：多呈现位共存时二次注册跳过、不触发内核重复注册拒绝
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            root.plugin(new InteractionPlugin(), null).awaitStartup();
            ToolsService tools = root.as(ToolsView.class).tools();
            InteractionService answers = root.as(AnswersView.class).answers();

            PresenterAssembly.registerInteractionTools(root, tools, answers,
                    () -> { throw new IllegalStateException("本用例不触发会话解析"); }, () -> { });
            PresenterAssembly.registerInteractionTools(root, tools, answers,
                    () -> { throw new IllegalStateException("重复注册应被跳过"); }, () -> { });

            List<String> names = tools.list().stream().map(ToolDefinition::name).toList();
            assertEquals(1, names.stream().filter("ask_user"::equals).count(), "ask_user 恰注册一次");
            assertEquals(1, names.stream().filter("exit_plan_mode"::equals).count(), "exit_plan_mode 恰注册一次");
        } finally {
            root.dispose();
        }
    }

    @Test
    void llmAdapterPropagatesConfigLoadFailure() {
        // LLM 配置缺失/不合法时装配即失败点名——呈现位据此 FAILED（沿 WebPlugin 既有语义）
        assertThrows(PluginException.class,
                () -> PresenterAssembly.llmAdapter(dev.duo.harness.llm.LlmConfig.load(
                        tempDir.resolve("不存在的config.yml"), java.util.Map.of())));
    }
}
