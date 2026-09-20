package dev.duo.harness.attachment;

/** 请求变体产物：变体字节与元数据（variantId 寻址，供请求组装为多部件 image）。 */
public record RequestVariant(String variantId, String mediaType, byte[] bytes, int width, int height) {
}
