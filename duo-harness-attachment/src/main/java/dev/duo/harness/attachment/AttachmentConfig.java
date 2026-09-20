package dev.duo.harness.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.PluginException;

/**
 * 附件域配置（ADR-0022）：源图与规范化产物的尺寸/字节预算。字段可省（省略即缺省，
 * 对齐 DSH 量级），类型/数值非法启动即 FAILED 点名，不做静默纠正。
 */
record AttachmentConfig(
        long maxImageBytes,
        int maxImagesPerMessage,
        long maxMessageImageBytes,
        long maxImagePixels,
        int maxImageDimension,
        long normalizedImageMaxPixels,
        int normalizedImageMaxDimension,
        long normalizedImageMaxBytes) {

    static final long DEFAULT_MAX_IMAGE_BYTES = 20L * 1024 * 1024;
    static final int DEFAULT_MAX_IMAGES_PER_MESSAGE = 20;
    static final long DEFAULT_MAX_MESSAGE_IMAGE_BYTES = 100L * 1024 * 1024;
    static final long DEFAULT_MAX_IMAGE_PIXELS = 64_000_000L;
    static final int DEFAULT_MAX_IMAGE_DIMENSION = 8192;
    static final long DEFAULT_NORMALIZED_IMAGE_MAX_PIXELS = 2048L * 2048;
    static final int DEFAULT_NORMALIZED_IMAGE_MAX_DIMENSION = 8192;
    static final long DEFAULT_NORMALIZED_IMAGE_MAX_BYTES = 4L * 1024 * 1024;

    static AttachmentConfig defaults() {
        return new AttachmentConfig(DEFAULT_MAX_IMAGE_BYTES, DEFAULT_MAX_IMAGES_PER_MESSAGE,
                DEFAULT_MAX_MESSAGE_IMAGE_BYTES, DEFAULT_MAX_IMAGE_PIXELS, DEFAULT_MAX_IMAGE_DIMENSION,
                DEFAULT_NORMALIZED_IMAGE_MAX_PIXELS, DEFAULT_NORMALIZED_IMAGE_MAX_DIMENSION,
                DEFAULT_NORMALIZED_IMAGE_MAX_BYTES);
    }

    /** yml config 解析：config 块可省；字段可省；类型/数值非法点名。 */
    static AttachmentConfig parse(JsonNode config) {
        if (config == null || config.isNull()) {
            return defaults();
        }
        long maxImageBytes = DEFAULT_MAX_IMAGE_BYTES;
        int maxImagesPerMessage = DEFAULT_MAX_IMAGES_PER_MESSAGE;
        long maxMessageImageBytes = DEFAULT_MAX_MESSAGE_IMAGE_BYTES;
        long maxImagePixels = DEFAULT_MAX_IMAGE_PIXELS;
        int maxImageDimension = DEFAULT_MAX_IMAGE_DIMENSION;
        long normalizedImageMaxPixels = DEFAULT_NORMALIZED_IMAGE_MAX_PIXELS;
        int normalizedImageMaxDimension = DEFAULT_NORMALIZED_IMAGE_MAX_DIMENSION;
        long normalizedImageMaxBytes = DEFAULT_NORMALIZED_IMAGE_MAX_BYTES;

        if (config.hasNonNull("maxImageBytes")) {
            maxImageBytes = positiveLong(config, "maxImageBytes");
        }
        if (config.hasNonNull("maxImagesPerMessage")) {
            maxImagesPerMessage = positiveInt(config, "maxImagesPerMessage");
        }
        if (config.hasNonNull("maxMessageImageBytes")) {
            maxMessageImageBytes = positiveLong(config, "maxMessageImageBytes");
        }
        if (config.hasNonNull("maxImagePixels")) {
            maxImagePixels = positiveLong(config, "maxImagePixels");
        }
        if (config.hasNonNull("maxImageDimension")) {
            maxImageDimension = positiveInt(config, "maxImageDimension");
        }
        if (config.hasNonNull("normalizedImageMaxPixels")) {
            normalizedImageMaxPixels = positiveLong(config, "normalizedImageMaxPixels");
        }
        if (config.hasNonNull("normalizedImageMaxDimension")) {
            normalizedImageMaxDimension = positiveInt(config, "normalizedImageMaxDimension");
        }
        if (config.hasNonNull("normalizedImageMaxBytes")) {
            normalizedImageMaxBytes = positiveLong(config, "normalizedImageMaxBytes");
        }
        return new AttachmentConfig(maxImageBytes, maxImagesPerMessage, maxMessageImageBytes,
                maxImagePixels, maxImageDimension, normalizedImageMaxPixels,
                normalizedImageMaxDimension, normalizedImageMaxBytes);
    }

    private static long positiveLong(JsonNode config, String field) {
        JsonNode node = config.get(field);
        if (!node.canConvertToLong() || node.asLong() <= 0) {
            throw new PluginException("attachment 插件 config 非法：" + field + " 须为正整数，实际 " + node);
        }
        return node.asLong();
    }

    private static int positiveInt(JsonNode config, String field) {
        JsonNode node = config.get(field);
        if (!node.canConvertToInt() || node.asInt() <= 0) {
            throw new PluginException("attachment 插件 config 非法：" + field + " 须为正整数，实际 " + node);
        }
        return node.asInt();
    }
}
