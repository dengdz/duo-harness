package dev.duo.harness.agent.governance;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 上下文预算计量用例：本地估算纯函数的表驱动验证（provider 无关、上取整宁早触发）。 */
class ContextBudgetTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ContextBudgetTest —— 上下文预算计量：估算纯函数表驱动（1 用例） ===");
    }

    @Test
    void estimatesTokensFromCharLengthRoundedUp() {
        assertEquals(0, ContextBudget.estimateTokens(""), "空文本零 token");
        assertEquals(0, ContextBudget.estimateTokens(null), "null 零 token");
        assertEquals(1, ContextBudget.estimateTokens("abc"), "3 字符上取整为 1 token");
        assertEquals(1, ContextBudget.estimateTokens("abcd"), "4 字符恰 1 token");
        assertEquals(2, ContextBudget.estimateTokens("abcde"), "5 字符上取整为 2 token");
        assertEquals(25, ContextBudget.estimateTokens("a".repeat(100)), "100 字符 25 token");
    }
}
