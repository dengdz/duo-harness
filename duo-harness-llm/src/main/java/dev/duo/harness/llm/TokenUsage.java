package dev.duo.harness.llm;

/**
 * Provider 报告的真实 token 用量（流式响应末尾的 usage 统计帧）：治理计量与
 * 状态展示的优先数据源——本地字符估算仅在 provider 未报告时兜底（ADR-0009）。
 *
 * @param promptTokens     本轮请求的上下文消耗（含 system 与全部历史）
 * @param completionTokens 本轮响应的输出消耗
 * @param totalTokens      provider 报告的总计（原样保留，不做本地推算）
 * @param cachedTokens     缓存命中 token（M25 工单 06 用量透出）：promptTokens 中
 *                         provider 报告的缓存读取部分（anthropic cache_read_input_tokens /
 *                         openai-compat prompt_tokens_details.cached_tokens）；provider
 *                         未报告缓存时 0——计费差异按 provider 缓存价自查，本字段只透事实
 */
public record TokenUsage(long promptTokens, long completionTokens, long totalTokens,
                         long cachedTokens) {

    /** 兼容构造：无缓存统计（旧 provider 形态——cachedTokens = 0）。 */
    public TokenUsage(long promptTokens, long completionTokens, long totalTokens) {
        this(promptTokens, completionTokens, totalTokens, 0);
    }
}
