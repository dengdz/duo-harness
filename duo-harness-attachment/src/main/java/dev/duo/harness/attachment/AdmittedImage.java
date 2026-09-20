package dev.duo.harness.attachment;

/** 入库后的附件元数据：attachmentId 即规范化字节的 SHA-256（hex），宽高为规范对象的尺寸。 */
record AdmittedImage(String attachmentId, String mediaType, long bytes, int width, int height) {
}
