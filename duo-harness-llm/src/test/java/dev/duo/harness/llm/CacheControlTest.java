package dev.duo.harness.llm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * cacheControl 三级断点与 provider 映射用例（M25 工单 06）：断点划分纯函数、
 * TokenUsage 缓存字段往返、四行映射策略。
 */
class CacheControlTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：CacheControlTest —— 缓存断点：三级划分、"
                + "TokenUsage 缓存字段、provider 映射（3 用例） ===");
    }

    @Test
    void 三级划分_身份前缀与稳定身份切分() {
        // userPrompt 为边界：system = userPrompt（身份前缀）+ \n\n + 片段（稳定身份）
        String system = "你是在 duo-harness 中运行的助手。\n\n"
                + "## AGENTS.md 约定\n第一条\n\n## memory 记忆本\n第二条\n\n## 技能清单\n第三条";
        CacheControl.Segments segments = CacheControl.split(system);
        assertEquals("你是在 duo-harness 中运行的助手。", segments.identityPrefix(),
                "首段（首个空行边界前）为身份前缀");
        assertTrue(segments.stableBody().startsWith("## AGENTS.md 约定"),
                "其余为稳定身份段: " + segments.stableBody());
    }

    @Test
    void 三级划分_无边界时全段为身份前缀() {
        CacheControl.Segments segments = CacheControl.split("单一指令没有空行边界");
        assertEquals("单一指令没有空行边界", segments.identityPrefix());
        assertNull(segments.stableBody(), "无稳定段——单断点形态（null 不装配第二块）");
    }

    @Test
    void TokenUsage缓存字段往返() {
        TokenUsage usage = new TokenUsage(1000, 200, 1200, 800);
        assertEquals(800, usage.cachedTokens(), "缓存命中 token 可读");
        TokenUsage plain = new TokenUsage(1000, 200, 1200);
        assertEquals(0, plain.cachedTokens(), "旧形态缺省 0——兼容无缓存 provider");
    }
}
