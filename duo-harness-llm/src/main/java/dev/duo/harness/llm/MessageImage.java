package dev.duo.harness.llm;

import java.util.Objects;

/** 多部件消息中的图片部件：base64 数据 + 媒体类型（OpenAI 兼容 image_url data URI 形态）。 */
public record MessageImage(String base64Data, String mediaType) {

    public MessageImage {
        Objects.requireNonNull(base64Data, "base64Data");
        Objects.requireNonNull(mediaType, "mediaType");
    }

    /** data URI 形态（data:<mediaType>;base64,<data>）——OpenAI 兼容 image_url 直接可用。 */
    public String dataUri() {
        return "data:" + mediaType + ";base64," + base64Data;
    }
}
