package dev.duo.harness.mcp.internal;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 重连策略纯函数用例：退避序列、稳定窗口清零、预算判定。 */
class ReconnectPolicyTest {

    @Test
    void backoffDoublesAndCaps() {
        ReconnectPolicy policy = new ReconnectPolicy(30_000, 100, 800, 10);
        assertEquals(100, policy.backoffDelayMs(1));
        assertEquals(200, policy.backoffDelayMs(2));
        assertEquals(400, policy.backoffDelayMs(3));
        assertEquals(800, policy.backoffDelayMs(4));
        assertEquals(800, policy.backoffDelayMs(9));
    }

    @Test
    void stableWindowResetsFailureCount() {
        ReconnectPolicy policy = new ReconnectPolicy(30_000, 100, 800, 10);
        // 存活满稳定窗口后断开：计数清零后记 1
        assertEquals(1, policy.failuresAfterDrop(60_000, 7));
        // 存活未满窗口：累计
        assertEquals(8, policy.failuresAfterDrop(1_000, 7));
    }

    @Test
    void budgetExhaustionAtMaxAttempts() {
        ReconnectPolicy policy = new ReconnectPolicy(30_000, 100, 800, 3);
        assertFalse(policy.budgetExhausted(2));
        assertTrue(policy.budgetExhausted(3));
    }

    @Test
    void zeroUptimeDropAccumulatesCount() {
        // 初始化即失败（uptime=0）：无论旧计数多少都严格累计
        ReconnectPolicy policy = new ReconnectPolicy(30_000, 100, 800, 10);
        assertEquals(5, policy.failuresAfterDrop(0, 4));
    }
}
