package dev.duo.harness.agent.presenter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.commands.CommandScope;
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
import dev.duo.harness.tools.fs.WorkspacePolicy;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    interface CommandsView {

        dev.duo.harness.agent.commands.CommandsRegistry commands();
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

            PresenterAssembly.registerInteractionTools(root, tools, answers, "cli",
                    () -> { throw new IllegalStateException("本用例不触发会话解析"); }, () -> { });
            // 二次注册（另一呈现位）：工具实例跳过，但亲和会话供给应补记进既有实例
            PresenterAssembly.registerInteractionTools(root, tools, answers, "web",
                    () -> { throw new IllegalStateException("重复注册应被跳过"); }, () -> { });

            List<String> names = tools.list().stream().map(ToolDefinition::name).toList();
            assertEquals(1, names.stream().filter("ask_user"::equals).count(), "ask_user 恰注册一次");
            assertEquals(1, names.stream().filter("exit_plan_mode"::equals).count(), "exit_plan_mode 恰注册一次");
        } finally {
            root.dispose();
        }
    }

    @Test
    void compactCommandRegistersOncePerName() {
        // /compact 查重先到先得（M19）：双呈现位共存时二次注册跳过——同名 fail-fast 的
        // 注册表语义下，装配层的查重是双面命令的唯一安全注册方式
        Context root = Context.root();
        try {
            root.plugin(new dev.duo.harness.agent.commands.CommandsPlugin(),
                    JsonNodeFactory.instance.objectNode()).awaitStartup();
            var commands = root.as(CommandsView.class).commands();
            ContextGovernance governance = ContextGovernanceTestHarness.dummy();

            PresenterAssembly.registerCompactCommand(root, commands, governance);
            PresenterAssembly.registerCompactCommand(root, commands, governance);

            assertEquals(1, commands.all().size(), "compact 恰注册一次");
            assertEquals(CommandScope.ANY, commands.find("compact").scope(), "双面可用");
            assertFalse(commands.find("compact").busySafe(), "动上下文必须 idle（busySafe=false）");
        } finally {
            root.dispose();
        }
    }

    /** 治理测试桩：compactNow 不被本用例触发，仅占位。 */
    private static final class ContextGovernanceTestHarness {
        private static ContextGovernance dummy() {
            return new ContextGovernance(new dev.duo.harness.llm.LlmAdapter() {
                @Override
                public void stream(dev.duo.harness.llm.ChatRequest request,
                                   java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public dev.duo.harness.llm.LlmTurn streamTurn(
                        dev.duo.harness.llm.ChatRequest request,
                        java.util.function.Consumer<String> textSink) {
                    throw new UnsupportedOperationException();
                }
            });
        }
    }

    @Test
    void permissionModeRestoreRespectsResetPolicy() throws Exception {
        // BUG-20260919-03（M19-06 验收实测）：恢复语义分档——启动续接（reset=false）
        // 无切档记录保持现状不重置（双开下不得覆盖另一呈现位刚恢复的档位）；
        // 显式换绑（reset=true）无记录重置回装配档
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            root.plugin(new dev.duo.harness.tools.fs.FsToolsPlugin(),
                    JsonNodeFactory.instance.objectNode().put("mode", "workspace-write"))
                    .awaitStartup();
            WorkspacePolicy workspace = root.as(WorkspaceView.class).workspace();

            // 会话无切档记录
            dev.duo.harness.session.Session fresh = dev.duo.harness.session.Session.create(
                    java.nio.file.Path.of(tempDir.toAbsolutePath().toString(), "s"));
            // 模拟"另一呈现位刚恢复过 read-only"的全局现状
            workspace.setMode(WorkspacePolicy.Mode.parse("read-only"));

            PresenterAssembly.restorePermissionMode(root, fresh, false);
            assertEquals("read-only", workspace.mode().configName(),
                    "启动续接：无记录保持现状（不覆盖另一呈现位的恢复）");

            PresenterAssembly.restorePermissionMode(root, fresh, true);
            assertEquals("workspace-write", workspace.mode().configName(),
                    "显式换绑：无记录重置回装配档");
            fresh.close();

            // 有切档记录：两种模式都恢复记录档
            dev.duo.harness.session.Session switched = dev.duo.harness.session.Session.create(
                    java.nio.file.Path.of(tempDir.toAbsolutePath().toString(), "s"));
            switched.append(dev.duo.harness.session.SessionEvent.permissionMode("read-only"));
            PresenterAssembly.restorePermissionMode(root, switched, false);
            assertEquals("read-only", workspace.mode().configName(), "有记录照常恢复");
            switched.close();
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

    @Test
    void maxParallelToolCallsDefaultsToTenAndBindsStrictly() throws IOException {
        // ADR-0018：并发度可配——缺省 10；=1 即完全串行（排障开关）；严格绑定
        assertEquals(10, PresenterAssembly.parseMaxParallelToolCalls(null), "config 缺失取缺省");
        assertEquals(10, PresenterAssembly.parseMaxParallelToolCalls(config("{\"port\":8080}")),
                "字段缺席取缺省（不配置并发照常生效）");
        assertEquals(1, PresenterAssembly.parseMaxParallelToolCalls(config("{\"maxParallelToolCalls\":1}")),
                "=1 即完全串行（排障开关）");

        PluginException fractional = assertThrows(PluginException.class,
                () -> PresenterAssembly.parseMaxParallelToolCalls(config("{\"maxParallelToolCalls\":2.5}")),
                "小数点名拒绝");
        assertTrue(fractional.getMessage().contains("maxParallelToolCalls"), "异常点名字段");
        assertThrows(PluginException.class,
                () -> PresenterAssembly.parseMaxParallelToolCalls(config("{\"maxParallelToolCalls\":\"10\"}")),
                "字符串点名拒绝");
        assertThrows(PluginException.class,
                () -> PresenterAssembly.parseMaxParallelToolCalls(config("{\"maxParallelToolCalls\":0}")),
                "非正点名拒绝");
    }

    @Test
    void pipelineTimeoutMsDefaultsTo120sAndBindsStrictly() throws IOException {
        // ADR-0018：管线缺省超时可配——缺省 120s；严格绑定
        assertEquals(120_000L, PresenterAssembly.parsePipelineTimeoutMs(null), "config 缺失取缺省");
        assertEquals(120_000L, PresenterAssembly.parsePipelineTimeoutMs(config("{\"port\":8080}")),
                "字段缺席取缺省（不配置兜底照常生效）");
        assertEquals(30_000L, PresenterAssembly.parsePipelineTimeoutMs(config("{\"pipelineTimeoutMs\":30000}")),
                "显式值生效");

        PluginException fractional = assertThrows(PluginException.class,
                () -> PresenterAssembly.parsePipelineTimeoutMs(config("{\"pipelineTimeoutMs\":1.5}")),
                "小数点名拒绝");
        assertTrue(fractional.getMessage().contains("pipelineTimeoutMs"), "异常点名字段");
        assertThrows(PluginException.class,
                () -> PresenterAssembly.parsePipelineTimeoutMs(config("{\"pipelineTimeoutMs\":\"120000\"}")),
                "字符串点名拒绝");
        assertThrows(PluginException.class,
                () -> PresenterAssembly.parsePipelineTimeoutMs(config("{\"pipelineTimeoutMs\":0}")),
                "非正点名拒绝");
    }
    /** workspace 服务的视图接口（方法名即服务名 "workspace"）。 */
    interface WorkspaceView {

        dev.duo.harness.tools.fs.WorkspacePolicy workspace();
    }
}
