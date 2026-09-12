package dev.duo.harness.llm;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.core.api.PluginException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LlmConfig 用例：文件基线 + env 覆盖优先、缺文件 env 兜底、缺项点名。 */
class LlmConfigTest {

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

    private Path tempConfig(String baseUrl, String apiKey, String model) throws Exception {
        ObjectNode llm = JsonNodeFactory.instance.objectNode();
        llm.put("baseUrl", baseUrl).put("apiKey", apiKey).put("model", model);
        ObjectNode root = JsonNodeFactory.instance.objectNode().set("llm", llm);
        return Files.writeString(tempDir.resolve("config.yml"), root.toString());
    }
}
