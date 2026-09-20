package dev.duo.harness.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.PluginException;

/**
 * web 工具族插件配置（ADR-0021 决策 6）：限额与超时的部署面——全部不给模型。
 * 缺省对齐 DSH（超时 30s / 响应体 5MB / 正文 100k 字符 / 输出 200k 字符 / 重定向 5 跳）。
 * 解析纪律与 WebPlugin.parsePageSize 同款：字段可省（省略即缺省），类型/数值非法
 * 启动即 FAILED 点名，不做静默纠正。
 */
record WebToolsConfig(
        int timeoutMs,
        long maxResponseBytes,
        int maxBodyChars,
        int maxOutputChars,
        int maxRedirects,
        String userAgent,
        SearchConfig search) {

    /** 缺省 UA：去版本化（避免随发布散点改码）；需要版本标识的部署用 userAgent 字段自配。 */
    static final String DEFAULT_USER_AGENT = "duo-harness";
    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 5_000_000L;
    private static final int DEFAULT_MAX_BODY_CHARS = 100_000;
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 200_000;
    private static final int DEFAULT_MAX_REDIRECTS = 5;

    /** 便捷构造（无搜索段）——fetch 侧测试与缺省装配用。 */
    WebToolsConfig(int timeoutMs, long maxResponseBytes, int maxBodyChars, int maxOutputChars,
                   int maxRedirects, String userAgent) {
        this(timeoutMs, maxResponseBytes, maxBodyChars, maxOutputChars, maxRedirects, userAgent, null);
    }

    /**
     * 搜索段配置（ADR-0021 决策 3/7）：首发 provider 为 tavily；key 解析链为
     * 字面量 → 环境变量兜底，皆空视为未配置（web_search 不注册）。
     */
    record SearchConfig(String type, String apiKey, String apiKeyEnv, String baseUrl, int maxResults) {

        static final String TYPE_TAVILY = "tavily";
        static final String DEFAULT_API_KEY_ENV = "TAVILY_API_KEY";
        static final String DEFAULT_BASE_URL = "https://api.tavily.com";
        static final int DEFAULT_MAX_RESULTS = 8;

        /** 生效 key：字面量优先、环境变量兜底；皆空返回 null（= search 段视为未配置）。 */
        String resolveApiKey(java.util.function.UnaryOperator<String> envLookup) {
            if (apiKey != null && !apiKey.isBlank()) {
                return apiKey.strip();
            }
            String envName = (apiKeyEnv == null || apiKeyEnv.isBlank()) ? DEFAULT_API_KEY_ENV : apiKeyEnv.strip();
            String fromEnv = envLookup.apply(envName);
            return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.strip();
        }

        String effectiveBaseUrl() {
            return (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.strip();
        }

        int effectiveMaxResults() {
            return maxResults <= 0 ? DEFAULT_MAX_RESULTS : maxResults;
        }
    }

    static WebToolsConfig defaults() {
        return new WebToolsConfig(DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RESPONSE_BYTES, DEFAULT_MAX_BODY_CHARS,
                DEFAULT_MAX_OUTPUT_CHARS, DEFAULT_MAX_REDIRECTS, DEFAULT_USER_AGENT);
    }

    /** yml config 解析：config 块可省；字段可省；类型/数值非法点名。 */
    static WebToolsConfig parse(JsonNode config) {
        if (config == null || config.isNull()) {
            return defaults();
        }
        int timeoutMs = DEFAULT_TIMEOUT_MS;
        long maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES; // long：>2GB 的字节上限不被 int 截断
        int maxBodyChars = DEFAULT_MAX_BODY_CHARS; // 转换后正文字符上限（head 重量级页面靠后置限额保正文，见 WebFetchTool）
        int maxOutputChars = DEFAULT_MAX_OUTPUT_CHARS;
        int maxRedirects = DEFAULT_MAX_REDIRECTS;
        String userAgent = DEFAULT_USER_AGENT;
        SearchConfig search = null;

        if (config.hasNonNull("timeoutMs")) {
            timeoutMs = positiveInt(config, "timeoutMs");
        }
        if (config.hasNonNull("maxResponseBytes")) {
            maxResponseBytes = positiveLong(config, "maxResponseBytes");
        }
        if (config.hasNonNull("maxBodyChars")) {
            maxBodyChars = positiveInt(config, "maxBodyChars");
        }
        if (config.hasNonNull("maxOutputChars")) {
            maxOutputChars = positiveInt(config, "maxOutputChars");
        }
        if (config.hasNonNull("maxRedirects")) {
            maxRedirects = nonNegativeInt(config, "maxRedirects");
        }
        if (config.hasNonNull("userAgent")) {
            JsonNode ua = config.get("userAgent");
            if (!ua.isTextual()) {
                throw new PluginException("web 插件 config 非法：userAgent 须为字符串，实际 " + ua);
            }
            if (ua.asText().isBlank()) {
                throw new PluginException("web 插件 config 非法：userAgent 不能为空白");
            }
            userAgent = ua.asText().strip();
        }
        if (config.hasNonNull("search")) {
            search = parseSearch(config.get("search"));
        }
        return new WebToolsConfig(timeoutMs, maxResponseBytes, maxBodyChars, maxOutputChars, maxRedirects,
                userAgent, search);
    }

    /** 文本字段读取：非字符串值点名（asText 会把数字静默转字符串，报错远离根因）。 */
    private static String textFieldOrNull(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        if (!value.isTextual()) {
            throw new PluginException("web 插件 config 非法：" + field + " 须为字符串，实际 " + value);
        }
        return value.asText();
    }

    /** search 段解析：type 必为 tavily（首发唯一实现），maxResults 正整数，baseUrl 非空白。 */
    private static SearchConfig parseSearch(JsonNode node) {
        if (!node.isObject()) {
            throw new PluginException("web 插件 config 非法：search 段须为对象");
        }
        String type = node.path("type").asText(SearchConfig.TYPE_TAVILY).strip();
        if (!type.equalsIgnoreCase(SearchConfig.TYPE_TAVILY)) {
            throw new PluginException("web 插件 config 非法：search.type 仅支持 " + SearchConfig.TYPE_TAVILY
                    + "，实际 " + type);
        }
        int maxResults = SearchConfig.DEFAULT_MAX_RESULTS;
        if (node.hasNonNull("maxResults")) {
            maxResults = positiveInt(node, "maxResults");
        }
        String baseUrl = textFieldOrNull(node, "baseUrl");
        String apiKey = textFieldOrNull(node, "apiKey");
        String apiKeyEnv = textFieldOrNull(node, "apiKeyEnv");
        if (apiKeyEnv != null && apiKeyEnv.isBlank()) {
            throw new PluginException("web 插件 config 非法：search.apiKeyEnv 不能为空白");
        }
        return new SearchConfig(type.toLowerCase(java.util.Locale.ROOT), apiKey, apiKeyEnv, baseUrl, maxResults);
    }

    private static long positiveLong(JsonNode config, String field) {
        JsonNode node = config.get(field);
        if (!node.canConvertToLong() || node.asLong() <= 0) {
            throw new PluginException("web 插件 config 非法：" + field + " 须为正整数，实际 " + node);
        }
        return node.asLong();
    }

    private static int positiveInt(JsonNode config, String field) {
        JsonNode node = config.get(field);
        if (!node.canConvertToInt() || node.asInt() <= 0) {
            throw new PluginException("web 插件 config 非法：" + field + " 须为正整数，实际 " + node);
        }
        return node.asInt();
    }

    private static int nonNegativeInt(JsonNode config, String field) {
        JsonNode node = config.get(field);
        if (!node.canConvertToInt() || node.asInt() < 0) {
            throw new PluginException("web 插件 config 非法：" + field + " 须为非负整数，实际 " + node);
        }
        return node.asInt();
    }
}
