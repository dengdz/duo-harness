package dev.duo.harness.llm;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.core.api.PluginException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LlmConfig 用例：文件基线 + env 覆盖优先、缺文件 env 兜底、缺项点名。 */
class LlmConfigTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：LlmConfigTest —— LLM 配置装载：必填校验、缺省值、错误点名（15 用例） ===");
    }

    private static final Path NO_FILE = Path.of("/nonexistent/config.yml");

    @TempDir
    Path tempDir;

    @Test
    void fileProvidesBaseline() throws Exception {
        Path config = tempDir.resolve("config.yml");
        Files.writeString(config, """
                llm:
                  baseUrl: https://api.deepseek.com
                  apiKey: sk-from-file
                  model: deepseek-chat
                  systemPrompt: 你是一个测试助手
                """);

        LlmConfig config1 = LlmConfig.load(config, Map.of());

        assertEquals("https://api.deepseek.com", config1.baseUrl());
        assertEquals("sk-from-file", config1.apiKey());
        assertEquals("deepseek-chat", config1.model());
        assertEquals("你是一个测试助手", config1.systemPrompt(), "yml 配置的 systemPrompt 应生效");
    }

    @Test
    void envOverridesFile() throws Exception {
        Path config = tempConfig("https://file.example", "sk-file", "file-model");

        LlmConfig config1 = LlmConfig.load(config, Map.of(
                "DUO_LLM_BASE_URL", "https://env.example",
                "DUO_LLM_MODEL", "env-model"));

        assertEquals("https://env.example", config1.baseUrl(), "env 应覆盖文件");
        assertEquals("sk-file", config1.apiKey(), "env 未提供的项应保留文件值");
        assertEquals("env-model", config1.model(), "env 应覆盖文件");
    }

    @Test
    void missingFileFallsBackToEnv() {
        LlmConfig config1 = LlmConfig.load(NO_FILE, Map.of(
                "DUO_LLM_BASE_URL", "https://env.example",
                "DUO_LLM_API_KEY", "sk-env",
                "DUO_LLM_MODEL", "env-model"));

        assertEquals("https://env.example", config1.baseUrl());
        assertEquals("sk-env", config1.apiKey());
    }

    @Test
    void missingApiKeyIsNamedLoudly() {
        PluginException e = assertThrows(PluginException.class,
                () -> LlmConfig.load(NO_FILE, Map.of(
                        "DUO_LLM_BASE_URL", "https://env.example",
                        "DUO_LLM_MODEL", "env-model")));

        assertTrue(e.getMessage().contains("apiKey=缺失"), e.getMessage());
        assertTrue(e.getMessage().contains("DUO_LLM_API_KEY"), "报错应给出重配指引: " + e.getMessage());
    }

    @Test
    void blankEnvValueDoesNotOverrideFile() throws Exception {
        Path config = tempConfig("https://file.example", "sk-file", "file-model");

        LlmConfig config1 = LlmConfig.load(config, Map.of("DUO_LLM_API_KEY", "  "));

        assertEquals("sk-file", config1.apiKey(), "空白 env 值不应覆盖文件");
    }

    @Test
    void providerDefaultsToOpenAiCompat() throws Exception {
        Path config = tempConfig("https://api.deepseek.com", "sk", "deepseek-chat");
        assertEquals(LlmConfig.PROVIDER_OPENAI_COMPAT, LlmConfig.load(config, Map.of()).provider(),
                "未声明 provider 缺省 openai-compat（兼容现状零改）");
    }

    @Test
    void providerParsesAllFourValues() throws Exception {
        for (String value : List.of("anthropic", "deepseek", "glm", "openai-compat")) {
            ObjectNode llm = JsonNodeFactory.instance.objectNode()
                    .put("baseUrl", "https://x").put("apiKey", "k").put("model", "m")
                    .put("provider", value);
            Path config = write(llm);
            assertEquals(value, LlmConfig.load(config, Map.of()).provider(), "合法值照常解析: " + value);
        }
    }

    @Test
    void unknownProviderFailsLoud() throws Exception {
        ObjectNode llm = JsonNodeFactory.instance.objectNode()
                .put("baseUrl", "https://x").put("apiKey", "k").put("model", "m")
                .put("provider", "openai"); // 拼写错误不静默落回兼容面
        Path config = write(llm);
        var exception = assertThrows(PluginException.class, () -> LlmConfig.load(config, Map.of()));
        assertTrue(exception.getMessage().contains("llm.provider 非法"), exception.getMessage());
    }

    @Test
    void blankProviderTreatedAsUnconfigured() throws Exception {
        // 空白串是"未配置"不是拼写错误——与 imageDelivery 空白归缺省同口径（行级轴 L6）
        ObjectNode llm = JsonNodeFactory.instance.objectNode()
                .put("baseUrl", "https://x").put("apiKey", "k").put("model", "m")
                .put("provider", "   ");
        Path config = write(llm);
        assertEquals(LlmConfig.PROVIDER_OPENAI_COMPAT, LlmConfig.load(config, Map.of()).provider());
    }

    private Path write(ObjectNode llm) throws Exception {
        ObjectNode root = JsonNodeFactory.instance.objectNode().set("llm", llm);
        return Files.writeString(tempDir.resolve("config.yml"), root.toString());
    }

    private Path tempConfig(String baseUrl, String apiKey, String model) throws Exception {
        ObjectNode llm = JsonNodeFactory.instance.objectNode();
        llm.put("baseUrl", baseUrl).put("apiKey", apiKey).put("model", model);
        ObjectNode root = JsonNodeFactory.instance.objectNode().set("llm", llm);
        return Files.writeString(tempDir.resolve("config.yml"), root.toString());
    }

    @Test
    void modelsWhitelistParsingAndFailLoud() throws Exception {
        // 缺席 → 空表（/model 不可切，M24 工单 09）；合法清单照常解析；非文本条目 FAILED 点名
        assertTrue(LlmConfig.load(tempConfig("https://x", "k", "m"), Map.of()).models().isEmpty(),
                "未声明 models 为空表");

        ObjectNode llm = JsonNodeFactory.instance.objectNode()
                .put("baseUrl", "https://x").put("apiKey", "k").put("model", "m");
        llm.putArray("models").add("deepseek-chat").add("claude-sonnet-4-5");
        Path config = write(llm);
        LlmConfig loaded = LlmConfig.load(config, Map.of());
        assertEquals(List.of("deepseek-chat", "claude-sonnet-4-5"), loaded.models());
        assertTrue(loaded.modelAllowed("deepseek-chat"));
        assertFalse(loaded.modelAllowed("gpt-x"));

        ObjectNode bad = JsonNodeFactory.instance.objectNode()
                .put("baseUrl", "https://x").put("apiKey", "k").put("model", "m");
        bad.putArray("models").add("deepseek-chat").add(42);
        assertThrows(PluginException.class, () -> LlmConfig.load(write(bad), Map.of()),
                "白名单坏条目 FAILED 点名（成本闸门不静默缩减）");
    }

    @Test
    void withModelSwapsOnlyModel() {
        LlmConfig base = new LlmConfig("https://x", "k", "旧模型", "sp", 5, 100, 1000, true,
                LlmConfig.DELIVERY_FILES, LlmConfig.PROVIDER_ANTHROPIC, List.of("a", "b"));
        LlmConfig swapped = base.withModel("新模型");
        assertEquals("新模型", swapped.model());
        assertEquals("https://x", swapped.baseUrl(), "withModel 只换模型名");
        assertEquals("k", swapped.apiKey());
        assertEquals("sp", swapped.systemPrompt());
        assertEquals(5, swapped.retryMaxAttempts());
        assertEquals(100, swapped.retryInitialBackoffMs());
        assertEquals(1000, swapped.streamIdleTimeoutMs());
        assertTrue(swapped.vision());
        assertEquals(LlmConfig.DELIVERY_FILES, swapped.imageDelivery());
        assertEquals(LlmConfig.PROVIDER_ANTHROPIC, swapped.provider());
        assertEquals(List.of("a", "b"), swapped.models());
    }

    @Test
    void effortDefaultsToMediumAndNormalizes() {
        // 工单 10：effort 缺省 medium；null/空白归一缺省、大小写与空白归一
        assertEquals(LlmConfig.DEFAULT_EFFORT,
                new LlmConfig("https://x", "k", "m", "sp").effort());
        assertEquals(LlmConfig.EFFORT_HIGH,
                new LlmConfig("https://x", "k", "m", "sp", 3, 1000, 90_000, false,
                        LlmConfig.DELIVERY_INLINE, LlmConfig.PROVIDER_ANTHROPIC,
                        List.of(), "  HIGH  ").effort());
        assertEquals(LlmConfig.DEFAULT_EFFORT,
                new LlmConfig("https://x", "k", "m", "sp", 3, 1000, 90_000, false,
                        LlmConfig.DELIVERY_INLINE, LlmConfig.PROVIDER_ANTHROPIC,
                        List.of(), " ").effort());
    }

    @Test
    void withEffortSwapsOnlyEffort() {
        // 工单 10：/effort 换链只换档位，其余配置原样保留（与 withModel 同构）
        LlmConfig base = new LlmConfig("https://x", "k", "m", "sp", 5, 100, 1000, true,
                LlmConfig.DELIVERY_FILES, LlmConfig.PROVIDER_ANTHROPIC, List.of("a", "b"),
                LlmConfig.EFFORT_MEDIUM);
        LlmConfig swapped = base.withEffort(LlmConfig.EFFORT_HIGH);
        assertEquals(LlmConfig.EFFORT_HIGH, swapped.effort());
        assertEquals("m", swapped.model(), "withEffort 不动模型名");
        assertEquals("k", swapped.apiKey());
        assertEquals(LlmConfig.PROVIDER_ANTHROPIC, swapped.provider());
        assertEquals(List.of("a", "b"), swapped.models());
        assertTrue(swapped.vision());
    }

    @Test
    void effortAllowedGatesFourLevels() {
        // 工单 10：/effort 切换前校验——合法四档（大小写不敏感）、非法与 null 拒绝
        assertTrue(LlmConfig.effortAllowed("OFF"));
        assertTrue(LlmConfig.effortAllowed(" low "));
        assertTrue(LlmConfig.effortAllowed("medium"));
        assertTrue(LlmConfig.effortAllowed("high"));
        assertTrue(!LlmConfig.effortAllowed("ultra"));
        assertTrue(!LlmConfig.effortAllowed(null));
    }

    @Test
    void effortNoteCoversFourProviderRows() {
        // 工单 10「永不静默」：四行映射说明——不支持参数的 provider 显式降级标注
        assertTrue(LlmConfig.effortNote(LlmConfig.PROVIDER_ANTHROPIC)
                .contains("thinking+budget"), "Anthropic 行：thinking+budget");
        assertTrue(LlmConfig.effortNote(LlmConfig.PROVIDER_DEEPSEEK)
                .contains("reasoner"), "DeepSeek 行：降级标注指向 reasoner 模型");
        assertTrue(LlmConfig.effortNote(LlmConfig.PROVIDER_GLM)
                .contains("开关"), "GLM 行：开关二值化说明");
        assertTrue(LlmConfig.effortNote(LlmConfig.PROVIDER_OPENAI_COMPAT)
                .contains("reasoning_effort"), "openai-compat 行：reasoning_effort 直传");
    }
}
