package dev.duo.harness.agent.presenter;

import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.governance.ContextGovernance;
import dev.duo.harness.agent.prompt.PromptRegistry;
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
                + "交互工具查重注册、LLM 配置失败点名、governance 段解析与生效、maxIterations 解析（7 用例） ===");
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

    // ===== governance 段解析（工单 M13-04）：缺席回退 / 严格绑定 / 越界点名 =====

    private com.fasterxml.jackson.databind.JsonNode config(String json) throws IOException {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
    }

    @Test
    void missingGovernanceSectionYieldsNullTuning() throws IOException {
        // 段缺席/为 null → null Tuning → 治理器用缺省常量（0.7.0 行为零漂移）
        assertEquals(null, PresenterAssembly.parseGovernance(null), "config 缺失");
        assertEquals(null, PresenterAssembly.parseGovernance(config("{\"port\":8080}")), "无 governance 段");
        assertEquals(null, PresenterAssembly.parseGovernance(config("{\"governance\":null}")), "段为 null");
    }

    @Test
    void governanceSectionBindsToTuningWithStrictValidation() throws IOException {
        ContextGovernance.Tuning tuning = PresenterAssembly.parseGovernance(config("""
                {"governance": {"spillThresholdChars": 1000, "pruneThresholdChars": 500,
                  "compactionThresholdRatio": 0.5, "contextWindowTokens": 32000,
                  "keepRecentRatio": 0.1, "minRemoteMessages": 2}}"""));

        assertEquals(1000, tuning.spillThresholdChars());
        assertEquals(500, tuning.pruneThresholdChars());
        assertEquals(0.5, tuning.compactionThresholdRatio());
        assertEquals(32000L, tuning.contextWindowTokens());
        assertEquals(0.1, tuning.keepRecentRatio());
        assertEquals(2, tuning.minRemoteMessages());

        ContextGovernance.Tuning partial = PresenterAssembly.parseGovernance(
                config("{\"governance\": {\"contextWindowTokens\": 64000}}"));

        assertEquals(64000L, partial.contextWindowTokens(), "配置字段生效");
        assertEquals(null, partial.spillThresholdChars(), "省略字段保持 null → 治理器回退常量");

        PluginException unknown = assertThrows(PluginException.class,
                () -> PresenterAssembly.parseGovernance(
                        config("{\"governance\": {\"spillThreshold\": 1000}}")),
                "字段名拼错必须点名拒绝，不静默忽略");
        assertTrue(unknown.getMessage().contains("spillThreshold"), "异常点名字段");
        assertThrows(PluginException.class, () -> PresenterAssembly.parseGovernance(
                        config("{\"governance\": {\"spillThresholdChars\": \"很多\"}}")),
                "类型错点名拒绝");
        assertThrows(PluginException.class, () -> PresenterAssembly.parseGovernance(
                config("{\"governance\": {\"spillThresholdChars\": 0}}")), "阈值须为正");
        assertThrows(PluginException.class, () -> PresenterAssembly.parseGovernance(
                config("{\"governance\": {\"compactionThresholdRatio\": 1.5}}")), "比例须在 (0,1]");
        assertThrows(PluginException.class, () -> PresenterAssembly.parseGovernance(
                config("{\"governance\": {\"keepRecentRatio\": 1}}")), "保留比须在 [0,1)");
    }

    @Test
    void governanceTuningInjectsEffectiveThresholds() {
        // 装配消费断言：Tuning 注入治理器后阈值生效——压缩触发阈值 = 窗口 × 比例（occupancy 可观测）
        ContextGovernance governance = PresenterAssembly.governance(
                scriptedAdapter("占位"),
                new ContextGovernance.Tuning(null, null, 0.5, 32000L, null, null));

        assertEquals(16000L, governance.occupancyThresholdTokens(), "压缩阈值 = 32000 × 0.5");
        assertEquals(32000L, governance.occupancyWindowTokens(), "状态面窗口取生效配置");
    }

    @Test
    void maxIterationsDefaultsToTenAndBindsStrictly() throws IOException {
        // BUG-20260917-03：迭代上限可配——缺省 10 行为不变；显式值整数严格绑定
        assertEquals(10, PresenterAssembly.parseMaxIterations(null), "config 缺失取缺省");
        assertEquals(10, PresenterAssembly.parseMaxIterations(config("{\"port\":8080}")),
                "字段缺席取缺省（不配置零漂移）");
        assertEquals(30, PresenterAssembly.parseMaxIterations(config("{\"maxIterations\":30}")),
                "显式值生效");

        PluginException fractional = assertThrows(PluginException.class,
                () -> PresenterAssembly.parseMaxIterations(config("{\"maxIterations\":5.5}")),
                "小数点名拒绝");
        assertTrue(fractional.getMessage().contains("maxIterations"), "异常点名字段");
        assertThrows(PluginException.class,
                () -> PresenterAssembly.parseMaxIterations(config("{\"maxIterations\":\"30\"}")),
                "字符串点名拒绝");
        assertThrows(PluginException.class,
                () -> PresenterAssembly.parseMaxIterations(config("{\"maxIterations\":0}")),
                "非正点名拒绝");
    }
}
