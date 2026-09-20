package dev.duo.harness.llm;

import java.util.Objects;

/** 多部件消息中的图片部件：base64 数据 + 媒体类型（OpenAI 兼容 image_url data URI 形态）。 */
public record MessageImage(String base64Data, String mediaType, String fileId) {

    public MessageImage {
        Objects.requireNonNull(base64Data, "base64Data");
        Objects.requireNonNull(mediaType, "mediaType");
    }

    /** 兼容构造：inline base64 形态（无 file_id）。 */
    public MessageImage(String base64Data, String mediaType) {
        this(base64Data, mediaType, null);
    }

    /** data URI 形态（data:<mediaType>;base64,<data>）——OpenAI 兼容 image_url 直接可用。 */
    public String dataUri() {
        return "data:" + mediaType + ";base64," + base64Data;
    }

    /** files 投递形态（file_id 非空 = 已上传 Files API；base64Data 仍保留作回退底牌）。 */
    public boolean deliveredAsFile() {
        return fileId != null && !fileId.isBlank();
    }
}
