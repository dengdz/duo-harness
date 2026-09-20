package dev.duo.harness.tools.web;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 测试辅助：拼 web 插件 config JSON（与生产 yml 同形态）。 */
final class WebToolsConfigTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebToolsConfigTestSupport() { }

    /** 工具参数 JSON 直转 JsonNode。 */
    static com.fasterxml.jackson.databind.JsonNode args(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 带 search 段（apiKey 字面量，可为空白）的 config。 */
    static com.fasterxml.jackson.databind.JsonNode configWithSearch(String apiKey) {
        try {
            return MAPPER.readTree("{\"search\":{\"apiKey\":\"" + apiKey + "\"}}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 指定 search.type 的 config（非法类型校验用）。 */
    static com.fasterxml.jackson.databind.JsonNode configWithSearchType(String type) {
        try {
            return MAPPER.readTree("{\"search\":{\"type\":\"" + type + "\",\"apiKey\":\"k\"}}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
