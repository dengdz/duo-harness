package dev.duo.harness.mcp.internal;

/**
 * 重连退避策略：指数退避 + 稳定窗口清零 + 预算耗尽放弃（DSH 同款语义）。
 *
 * <p>纯函数计算，便于独立测试；状态（失败计数/稳定判定）由调用方持有。</p>
 */
final class ReconnectPolicy {

    /** 稳定窗口：连接存活满此时长视为稳定，断开时失败计数清零。 */
    private final long stableWindowMs;
    /** 退避起始延迟。 */
    private final long initialDelayMs;
    /** 退避上限（亦为稳定窗口的缺省来源之一）。 */
    private final long maxDelayMs;
    /** 连续失败预算：达到即放弃重连。 */
    private final int maxAttempts;

    ReconnectPolicy(long stableWindowMs, long initialDelayMs, long maxDelayMs, int maxAttempts) {
        this.stableWindowMs = stableWindowMs;
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 连接断开时应递增的失败计数：存活满稳定窗口则清零后计 1（此前是稳定连接），
     * 否则在旧计数上加 1。
     */
    int failuresAfterDrop(long connectedUptimeMs, int previousFailures) {
        return connectedUptimeMs >= stableWindowMs ? 1 : previousFailures + 1;
    }

    /** 是否已达预算上限（计数从 1 起算，达到 maxAttempts 即耗尽）。 */
    boolean budgetExhausted(int failures) {
        return failures >= maxAttempts;
    }

    /** 第 failures 次失败后的退避延迟：initial × 2^(failures-1)，封顶 maxDelay。 */
    long backoffDelayMs(int failures) {
        long delay = initialDelayMs;
        for (int i = 1; i < failures; i++) {
            delay = Math.min(delay * 2, maxDelayMs);
            if (delay >= maxDelayMs) {
                break;
            }
        }
        return delay;
    }
}
