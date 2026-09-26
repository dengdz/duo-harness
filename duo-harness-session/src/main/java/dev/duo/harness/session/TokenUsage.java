package dev.duo.harness.session;

/**
 * 会话事件携带的真实 token 用量（provider 流式响应末尾的统计）：assistant/message
 * 事件的可选载荷（ADR-0009）——治理计量与状态展示优先消费，本地字符估算仅在
 * 事件缺失此字段时兜底。与 llm 域的 provider 原始统计同构：域间经 agent 映射，
 * 互不依赖。
 *
 * @param promptTokens     本轮请求的上下文消耗（含 system 与全部历史）
 * @param completionTokens 本轮响应的输出消耗
 * @param totalTokens      provider 报告的总计（原样保留，不做本地推算）
 * @param cachedTokens     缓存命中 token（M25 工单 06）：promptTokens 中 provider 报告的
 *                         缓存读取部分；未报告为 0（旧日志缺字段同 0）
 */
public record TokenUsage(long promptTokens, long completionTokens, long totalTokens,
                         long cachedTokens) {

    /** 兼容构造：无缓存统计（旧调用点与旧日志形态）。 */
    public TokenUsage(long promptTokens, long completionTokens, long totalTokens) {
        this(promptTokens, completionTokens, totalTokens, 0);
    }
}
