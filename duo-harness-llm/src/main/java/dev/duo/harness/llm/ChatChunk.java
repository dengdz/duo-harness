package dev.duo.harness.llm;

import java.util.Objects;

/**
 * LLM 流式响应的一个增量片段：适配器每从 provider 收到一段输出就交付一个实例
 * （回调式推模式——SSE 行读到即推，不缓冲整段）。
 *
 * <p>M3 只有文本增量；M5 若引入工具调用 chunk（参数增量），以新 record 或
 * 扩展形态演进，不改变"逐段交付"的契约。</p>
 *
 * @param text 本次增量文本（拼接全部 chunk 即完整回复）
 */
public record ChatChunk(String text) {

    /** 构造时校验非空。 */
    public ChatChunk {
        Objects.requireNonNull(text, "text");
    }
}
