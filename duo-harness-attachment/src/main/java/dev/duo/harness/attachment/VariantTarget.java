package dev.duo.harness.attachment;

/** 请求变体目标：长边像素上限 + 字节预算——同值必得同变体（variantId 确定性的输入之一）。 */
public record VariantTarget(int longEdge, long byteBudget) {

    /** 一期统一缺省目标（模型目录级目标留接口，spec Out of Scope）。 */
    static final VariantTarget DEFAULT = new VariantTarget(2048, 4L * 1024 * 1024);
}
