package dev.duo.harness.llm;

import java.util.Objects;

/** 多部件消息中的图片部件：base64 数据 + 媒体类型（OpenAI 兼容 image_url data URI 形态）。 */
public record MessageImage(String base64Data, String mediaType, String fileId) {

    public MessageImage {
        Objects.requireNonNull(mediaType, "mediaType");
        // base64 与 file_id 二选一（可都给）：files 形态允许不驻留 base64 大数组。
        // 紧凑构造器里字段尚未赋值——不能调 deliveredAsFile()，就地内联判定
        if (base64Data == null && (fileId == null || fileId.isBlank())) {
            throw new IllegalArgumentException("base64Data 与 fileId 至少其一（inline 或 files）");
        }
    }

    /** 兼容构造：inline base64 形态（无 file_id）。 */
    public MessageImage(String base64Data, String mediaType) {
        this(base64Data, mediaType, null);
    }

    /** files 形态构造：只携 file_id（不驻留 base64 大数组）。 */
    public static MessageImage byFileId(String fileId, String mediaType) {
        return new MessageImage(null, mediaType, fileId);
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
