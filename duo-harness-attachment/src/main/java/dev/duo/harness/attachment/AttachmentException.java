package dev.duo.harness.attachment;

/** 附件域业务异常：准入拒绝 / 格式不符 / 超限等——消息面向模型与用户，带自纠指引。 */
public class AttachmentException extends RuntimeException {

    public AttachmentException(String message) {
        super(message);
    }
}
