package dev.duo.harness.llm;

import java.util.List;
import java.util.Objects;

/**
 * LLM 一轮调用的聚合结果：完整文本 + 模型发起的工具调用请求列表。
 *
 * <p>流式增量（文本与 tool_calls 分片）由适配器聚合为完整形态后交付——
 * 分片细节（SSE 帧结构、index 对齐）不泄漏给消费方。</p>
 *
 * @param text      完整回复文本（无文本输出时为空串）
 * @param toolCalls 模型发起的工具调用请求（按响应序；空 = 本轮无工具调用）
 */
public record LlmTurn(String text, List<ToolCallRequest> toolCalls) {

    /** 构造时校验非空与防御性拷贝——错误前移到构造点。 */
    public LlmTurn {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(toolCalls, "toolCalls");
        toolCalls = List.copyOf(toolCalls);
    }

    /** 本轮是否发起了工具调用。 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
