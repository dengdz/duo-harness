package dev.duo.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.boot.DuoHome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * @param streamIdleTimeoutMs  流式空闲超时毫秒（连续无新字节即中止，默认 {@link #DEFAULT_STREAM_IDLE_TIMEOUT_MS}）
 * @param vision      视觉开关（true = 接受图片输入）
 * @param imageDelivery 图片投递形态：inline（base64 data URI，缺省）| files（DeepSeek 形态
 *                      Files API 上传换 file_id，仅视觉部署可及）
 * @param provider    provider 声明（M24 工单 08，ADR-0026 决策七）：openai-compat（缺省）|
 *                    anthropic | deepseek | glm——决定适配器选型、鉴权头形态与思考等级
 *                    映射策略；不再由 baseUrl 隐式表达
 * @param models      可切模型白名单（M24 工单 09，ADR-0026 决策一/六；空 = /model 不可切。
 *                    模型名直接决定成本面，白名单即「收得住」）
 * @param effort      思考等级四档 off/low/medium/high（M24 工单 10，ADR-0026 决策六；
 *                    缺省 medium，/effort 切换经 withEffort 换链生效）
 *                    模型名直接决定成本面，白名单即「收得住」）
 */
public record LlmConfig(String baseUrl, String apiKey, String model, String systemPrompt,
                        int retryMaxAttempts, long retryInitialBackoffMs,
                        long streamIdleTimeoutMs, boolean vision, String imageDelivery,
                        String provider, List<String> models, String effort) {

    /** imageDelivery inline（缺省）。 */
    public static final String DELIVERY_INLINE = "inline";

    /** imageDelivery files（DeepSeek 形态 Files API）。 */
    public static final String DELIVERY_FILES = "files";

    /** provider 声明：OpenAI 兼容面（缺省——DeepSeek/GLM 等同协议端点通用）。 */
    public static final String PROVIDER_OPENAI_COMPAT = "openai-compat";

    /** provider 声明：Anthropic messages 协议（x-api-key + anthropic-version）。 */
    public static final String PROVIDER_ANTHROPIC = "anthropic";

    /** provider 声明：DeepSeek（走 OpenAI 兼容面，思考等级映射策略不同）。 */
    public static final String PROVIDER_DEEPSEEK = "deepseek";

    /** provider 声明：GLM（走 OpenAI 兼容面，思考等级映射策略不同）。 */
    public static final String PROVIDER_GLM = "glm";

    /** 思考等级四档（M24 工单 10，ADR-0026 决策六）：全 provider 归一档位词。 */
    public static final String EFFORT_OFF = "off";
    public static final String EFFORT_LOW = "low";
    public static final String EFFORT_MEDIUM = "medium";
    public static final String EFFORT_HIGH = "high";

    /** 思考等级缺省档：medium（思考能力可用又不烧大钱）。 */
    public static final String DEFAULT_EFFORT = EFFORT_MEDIUM;

    /** 思考等级合法档清单（/effort 展示与校验序）。 */
    public static final List<String> EFFORT_LEVELS =
            List.of(EFFORT_OFF, EFFORT_LOW, EFFORT_MEDIUM, EFFORT_HIGH);

    /** Anthropic thinking budget_tokens 档位映射：low。 */
    public static final int ANTHROPIC_BUDGET_LOW = 2_048;
    /** Anthropic thinking budget_tokens 档位映射：medium。 */
    public static final int ANTHROPIC_BUDGET_MEDIUM = 8_192;
    /** Anthropic thinking budget_tokens 档位映射：high。 */
    public static final int ANTHROPIC_BUDGET_HIGH = 16_384;

    /** systemPrompt 未配置时的缺省指令。 */
    public static final String DEFAULT_SYSTEM_PROMPT = "你是一个简洁可靠的助手。";

    /** 重试总尝试次数缺省值。 */
    public static final int DEFAULT_RETRY_MAX_ATTEMPTS = 3;

    /** 首次重试退避毫秒缺省值。 */
    public static final long DEFAULT_RETRY_INITIAL_BACKOFF_MS = 1000;

    /** 流式空闲超时缺省值（90s：思考模型的长间隔不误伤，半开连接不至于久等）。 */
    public static final long DEFAULT_STREAM_IDLE_TIMEOUT_MS = 90_000;

    /** 兼容构造：重试与空闲超时参数取缺省（3 次 / 1000ms / 90s），vision 关闭、inline 投递、openai-compat、无白名单。 */
    public LlmConfig(String baseUrl, String apiKey, String model, String systemPrompt) {
        this(baseUrl, apiKey, model, systemPrompt, DEFAULT_RETRY_MAX_ATTEMPTS,
                DEFAULT_RETRY_INITIAL_BACKOFF_MS, DEFAULT_STREAM_IDLE_TIMEOUT_MS, false,
                DELIVERY_INLINE, PROVIDER_OPENAI_COMPAT, List.of(), DEFAULT_EFFORT);
    }

    /** 兼容构造：vision 显式、投递 inline。 */
    public LlmConfig(String baseUrl, String apiKey, String model, String systemPrompt,
                     int retryMaxAttempts, long retryInitialBackoffMs,
                     long streamIdleTimeoutMs, boolean vision) {
        this(baseUrl, apiKey, model, systemPrompt, retryMaxAttempts,
                retryInitialBackoffMs, streamIdleTimeoutMs, vision, DELIVERY_INLINE,
                PROVIDER_OPENAI_COMPAT, List.of(), DEFAULT_EFFORT);
    }

    /** 兼容构造：effort 取缺省 medium（工单 10 前的 11 组件调用形态）。 */
    public LlmConfig(String baseUrl, String apiKey, String model, String systemPrompt,
                     int retryMaxAttempts, long retryInitialBackoffMs,
                     long streamIdleTimeoutMs, boolean vision, String imageDelivery,
                     String provider, List<String> models) {
        this(baseUrl, apiKey, model, systemPrompt, retryMaxAttempts,
                retryInitialBackoffMs, streamIdleTimeoutMs, vision, imageDelivery,
                provider, models, DEFAULT_EFFORT);
    }

    /** 投递形态与 provider 归一（校验在 load 处 fail-loud）；models 防御性拷贝（null 归空表）。 */
    public LlmConfig {
        if (imageDelivery == null || imageDelivery.isBlank()) {
            imageDelivery = DELIVERY_INLINE;
        } else {
            imageDelivery = imageDelivery.strip().toLowerCase(java.util.Locale.ROOT);
        }
        if (provider == null || provider.isBlank()) {
            provider = PROVIDER_OPENAI_COMPAT;
        } else {
            provider = provider.strip().toLowerCase(java.util.Locale.ROOT);
        }
        models = models == null ? List.of() : List.copyOf(models);
        if (effort == null || effort.isBlank()) {
            effort = DEFAULT_EFFORT;
        } else {
            effort = effort.strip().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** 模型切换（/model 执行绑定，M24 工单 09）：仅换模型名（strip 归一），其余配置原样保留（同 provider 约束由调用方把关）。 */
    public LlmConfig withModel(String newModel) {
        String stripped = java.util.Objects.requireNonNull(newModel, "newModel").strip();
        return new LlmConfig(baseUrl, apiKey, stripped,
                systemPrompt, retryMaxAttempts, retryInitialBackoffMs, streamIdleTimeoutMs,
                vision, imageDelivery, provider, models, effort);
    }

    /** 思考等级切换（/effort 执行绑定，M24 工单 10）：仅换档位（strip 归一），其余配置原样保留。 */
    public LlmConfig withEffort(String newEffort) {
        String stripped = java.util.Objects.requireNonNull(newEffort, "newEffort").strip();
        return new LlmConfig(baseUrl, apiKey, model,
                systemPrompt, retryMaxAttempts, retryInitialBackoffMs, streamIdleTimeoutMs,
                vision, imageDelivery, provider, models, stripped);
    }

    /** 思考等级是否为合法四档之一（/effort 切换前校验）。 */
    public static boolean effortAllowed(String candidate) {
        return candidate != null && EFFORT_LEVELS.contains(
                candidate.strip().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 档位在当前 provider 下的映射说明（M24 工单 10「永不静默」）：/effort 无参展示与
     * 切换响应携带——不支持参数的 provider 显式降级标注，不静默。
     */
    public static String effortNote(String provider) {
        String normalized = provider == null ? PROVIDER_OPENAI_COMPAT
                : provider.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case PROVIDER_ANTHROPIC -> "Anthropic：thinking+budget（off 关闭；low "
                    + ANTHROPIC_BUDGET_LOW + " / medium " + ANTHROPIC_BUDGET_MEDIUM
                    + " / high " + ANTHROPIC_BUDGET_HIGH + " budget_tokens）";
            case PROVIDER_DEEPSEEK -> "DeepSeek 不支持思考等级参数，档位不生效——思考请切 reasoner 模型（/model）";
            case PROVIDER_GLM -> "GLM 仅 thinking 开关：off=关闭，low/medium/high 均=开启（档位细粒度不生效）";
            default -> "reasoning_effort 直传（off 即不带该参数）";
        };
    }

    /** 当前模型是否在 /model 可切白名单内（空白名单 = 全部不可切）。 */
    public boolean modelAllowed(String candidate) {
        return models.contains(java.util.Objects.requireNonNull(candidate, "candidate"));
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
     * @param configFile config.yml 路径（不存在时按空配置处理，env 可兜底）。
     *                   注意：思考等级（effort）不在 yml 解析面——恒为缺省 medium 的运行时态，
     *                   仅经 /effort 切换（M24 工单 10；写 yml 亦被忽略，此处显式声明防「永不静默」缺口）
     * @param env        环境变量（测试注入用）
     * @return 加载并校验后的 LLM 配置
     * @throws PluginException 关键项缺失（消息含重配指引）或 YAML 解析失败
     */
    public static LlmConfig load(Path configFile, Map<String, String> env) {
        JsonNode llm = readLlmSection(configFile);
        String baseUrl = override(text(llm, "baseUrl"), env.get(ENV_BASE_URL));
        String apiKey = override(text(llm, "apiKey"), env.get(ENV_API_KEY));
        String model = override(text(llm, "model"), env.get(ENV_MODEL));
        if (model != null) {
            model = model.strip();
        }
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
                parseRetryMaxAttempts(llm), parseRetryInitialBackoffMs(llm),
                parseStreamIdleTimeoutMs(llm),
                llm != null && llm.path("vision").asBoolean(false),
                parseImageDelivery(llm),
                parseProvider(llm),
                parseModels(llm));
    }

    /**
     * 解析可选 models 白名单（缺省空表 = /model 不可切）：非数组或含非文本/空白条目
     * 启动即 FAILED 点名——白名单是成本闸门，坏条目不允许静默缩减。
     */
    private static List<String> parseModels(JsonNode llm) {
        if (llm == null || !llm.hasNonNull("models")) {
            return List.of();
        }
        JsonNode array = llm.get("models");
        if (!array.isArray()) {
            throw new PluginException("llm.models 非法: 应为模型名字符串数组");
        }
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode entry : array) {
            if (!entry.isTextual() || entry.asText().isBlank()) {
                throw new PluginException("llm.models 含非法条目: \"" + entry + "\"（应为非空模型名字符串）");
            }
            out.add(entry.asText().strip());
        }
        return List.copyOf(out);
    }

    /**
     * 解析可选 provider（缺省 openai-compat）：非四值的声明启动即 FAILED 点名
     * （M24 工单 08，ADR-0026 决策七——拼写错误不该静默落回兼容面）。
     */
    private static String parseProvider(JsonNode llm) {
        if (llm == null || !llm.hasNonNull("provider")) {
            return PROVIDER_OPENAI_COMPAT;
        }
        String normalized = llm.path("provider").asText(PROVIDER_OPENAI_COMPAT).strip()
                .toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            return PROVIDER_OPENAI_COMPAT; // 空白视为未配置（与 imageDelivery 同口径）
        }
        if (normalized.equals(PROVIDER_OPENAI_COMPAT) || normalized.equals(PROVIDER_ANTHROPIC)
                || normalized.equals(PROVIDER_DEEPSEEK) || normalized.equals(PROVIDER_GLM)) {
            return normalized;
        }
        throw new dev.duo.harness.core.api.PluginException("llm.provider 非法: \"" + normalized
                + "\"（可选 openai-compat | anthropic | deepseek | glm）");
    }

    /**
     * 解析可选 imageDelivery（缺省 inline）：非 inline/files 的值启动即 FAILED 点名
     * （M21 工单 06，拼写错误不该静默降级为 inline）。
     */
    private static String parseImageDelivery(JsonNode llm) {
        if (llm == null || !llm.hasNonNull("imageDelivery")) {
            return DELIVERY_INLINE;
        }
        String value = llm.path("imageDelivery").asText(DELIVERY_INLINE).strip();
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals(DELIVERY_FILES) || normalized.equals(DELIVERY_INLINE)) {
            return normalized;
        }
        throw new dev.duo.harness.core.api.PluginException("llm.imageDelivery 非法: \"" + value
                + "\"（可选 inline | files）");
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

    /** 解析可选 streamIdleTimeoutSeconds（非正数回落默认 90s，换算为毫秒）。 */
    private static long parseStreamIdleTimeoutMs(JsonNode llm) {
        long seconds = llm == null ? -1 : llm.path("streamIdleTimeoutSeconds").asLong(-1);
        return seconds >= 1 ? seconds * 1000 : DEFAULT_STREAM_IDLE_TIMEOUT_MS;
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
