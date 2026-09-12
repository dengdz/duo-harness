package dev.duo.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.boot.DuoHome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * LLM 调用配置：{@code ~/.duo/config.yml} 的 {@code llm} 段为主，
 * {@code DUO_LLM_*} 环境变量逐项覆盖（env 优先）。
 *
 * <p>加载对文件缺失宽容：config.yml 不存在但 env 三项齐全时照常工作；
 * 最终任一关键项缺失则点名报错（含重配指引）。密钥只经此机制从用户
 * home / 环境读取，永不入仓库（红线 2）。</p>
 *
 * @param baseUrl      provider 地址（如 https://api.deepseek.com）
 * @param apiKey       凭证
 * @param model        模型名（如 deepseek-chat）
 * @param systemPrompt 行为指令（可选；M6 起作为 prompt 注册表的最前用户片段）
 * @param retryMaxAttempts      重试总尝试次数（含首次，默认 {@link #DEFAULT_RETRY_MAX_ATTEMPTS}）
 * @param retryInitialBackoffMs 首次重试退避毫秒（×2 递增，默认 {@link #DEFAULT_RETRY_INITIAL_BACKOFF_MS}）
 */
public record LlmConfig(String baseUrl, String apiKey, String model, String systemPrompt,
                        int retryMaxAttempts, long retryInitialBackoffMs) {

    /** systemPrompt 未配置时的缺省指令。 */
    public static final String DEFAULT_SYSTEM_PROMPT = "你是一个简洁可靠的助手。";

    /** 重试总尝试次数缺省值。 */
    public static final int DEFAULT_RETRY_MAX_ATTEMPTS = 3;

    /** 首次重试退避毫秒缺省值。 */
    public static final long DEFAULT_RETRY_INITIAL_BACKOFF_MS = 1000;

    /** 兼容构造：重试参数取缺省（3 次 / 1000ms）。 */
    public LlmConfig(String baseUrl, String apiKey, String model, String systemPrompt) {
        this(baseUrl, apiKey, model, systemPrompt, DEFAULT_RETRY_MAX_ATTEMPTS, DEFAULT_RETRY_INITIAL_BACKOFF_MS);
    }


    /** env 覆盖项：baseUrl。 */
    public static final String ENV_BASE_URL = "DUO_LLM_BASE_URL";

    /** env 覆盖项：apiKey。 */
    public static final String ENV_API_KEY = "DUO_LLM_API_KEY";

    /** env 覆盖项：model。 */
    public static final String ENV_MODEL = "DUO_LLM_MODEL";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** 从 duo home 默认位置（{@code ~/.duo/config.yml}）与当前环境加载。 */
    public static LlmConfig load() {
        Path configFile = DuoHome.resolve().root().resolve("config.yml");
        return load(configFile, System.getenv());
    }

    /**
     * 加载：文件 {@code llm} 段为基线，env 逐项覆盖，最终三项缺一即点名。
     *
     * @param configFile config.yml 路径（不存在时按空配置处理，env 可兜底）
     * @param env        环境变量（测试注入用）
     * @return 加载并校验后的 LLM 配置
     * @throws PluginException 关键项缺失（消息含重配指引）或 YAML 解析失败
     */
    public static LlmConfig load(Path configFile, Map<String, String> env) {
        JsonNode llm = readLlmSection(configFile);
        String baseUrl = override(text(llm, "baseUrl"), env.get(ENV_BASE_URL));
        String apiKey = override(text(llm, "apiKey"), env.get(ENV_API_KEY));
        String model = override(text(llm, "model"), env.get(ENV_MODEL));
        String systemPrompt = text(llm, "systemPrompt");

        if (baseUrl == null || apiKey == null || model == null) {
            throw new PluginException("LLM 配置不完整: baseUrl=" + present(baseUrl)
                    + ", apiKey=" + present(apiKey) + ", model=" + present(model)
                    + "。请在 " + configFile + " 的 llm 段配置（baseUrl/apiKey/model），"
                    + "或以 " + ENV_BASE_URL + " / " + ENV_API_KEY + " / " + ENV_MODEL
                    + " 环境变量提供");
        }
        return new LlmConfig(baseUrl, apiKey, model,
                systemPrompt != null && !systemPrompt.isBlank() ? systemPrompt : DEFAULT_SYSTEM_PROMPT,
                parseRetryMaxAttempts(llm), parseRetryInitialBackoffMs(llm));
    }

    /** 解析可选 retry.maxAttempts（非正数回落默认）。 */
    private static int parseRetryMaxAttempts(JsonNode llm) {
        int value = llm == null ? 0 : llm.path("retry").path("maxAttempts").asInt(0);
        return value >= 1 ? value : DEFAULT_RETRY_MAX_ATTEMPTS;
    }

    /** 解析可选 retry.initialBackoffMs（负数回落默认）。 */
    private static long parseRetryInitialBackoffMs(JsonNode llm) {
        long value = llm == null ? -1 : llm.path("retry").path("initialBackoffMs").asLong(-1);
        return value >= 0 ? value : DEFAULT_RETRY_INITIAL_BACKOFF_MS;
    }

    private static String override(String fromFile, String fromEnv) {
        return fromEnv != null && !fromEnv.isBlank() ? fromEnv : fromFile;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 敏感值不回显：缺失显示"缺失"，其余一律"已配置"。 */
    private static String present(String value) {
        return value == null ? "缺失" : "已配置";
    }

    private static JsonNode readLlmSection(Path configFile) {
        if (!Files.exists(configFile)) {
            return null;
        }
        try {
            JsonNode root = YAML.readTree(configFile.toFile());
            return root == null ? null : root.get("llm");
        } catch (Exception e) {
            throw new PluginException("配置文件解析失败: " + configFile + "（应为 YAML，llm 段含 baseUrl/apiKey/model）", e);
        }
    }
}
