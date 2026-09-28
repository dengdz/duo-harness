package dev.duo.harness.attachment;

/** 请求变体目标：长边像素上限 + 字节预算——同值必得同变体（variantId 确定性的输入之一）。 */
public record VariantTarget(int longEdge, long byteBudget) {

    /** 请求变体字节预算缺省（当前与规范化预算同值 4MB，语义独立各自演进——不物理统一）。 */
    static final long DEFAULT_BYTE_BUDGET = 4L * 1024 * 1024;

    /** 一期统一缺省目标（模型目录级目标留接口，spec Out of Scope）。 */
    static final VariantTarget DEFAULT = new VariantTarget(2048, DEFAULT_BYTE_BUDGET);
}
