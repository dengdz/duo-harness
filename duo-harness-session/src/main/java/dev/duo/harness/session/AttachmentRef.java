package dev.duo.harness.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;

/**
 * 附件引用（M21，ADR-0022）：会话日志中附件的轻量元数据——字节在附件库
 * （~/.duo/attachments，内容寻址、永不删除），日志零字节；attachmentId 即
 * 附件库的规范化字节 SHA-256（hex）。
 *
 * <p>JSON 序列化即会话日志的持久化形态（user/attachment 事件的 text）；
 * 授权读取端点以"日志引用了此 id"为出字节的前提。</p>
 */
public record AttachmentRef(String attachmentId, String mediaType, long bytes, String name) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 序列化为引用 JSON（user/attachment 事件的持久化形态）。 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (IOException e) {
            throw new IllegalStateException("附件引用序列化失败", e);
        }
    }

    /** 从引用 JSON 解析；坏行返回 empty（读取端点跳过，不让单行损坏炸穿扫描）。 */
    public static Optional<AttachmentRef> from(String json) {
        try {
            return Optional.of(MAPPER.readValue(json, AttachmentRef.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
