package dev.duo.harness.hooks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * matcher 三档语义用例（纯单元，ADR-0019 决策 3）：全匹配、精确名/多选（`|` 与 `,`）、
 * 未锚定正则（{@code Edit.*} 亦命中 NotebookEdit 的 Claude Code 同款语义）、
 * 非法正则防御兜底。
 */
class HookMatcherTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：HookMatcherTest —— matcher 三档：全匹配、精确/多选、"
                + "未锚定正则、非法防御（7 用例） ===");
    }

    @Test
    void absentBlankOrStarMatchesEverything() {
        assertTrue(HookMatcher.matches(null, "anything"));
        assertTrue(HookMatcher.matches("", "anything"));
        assertTrue(HookMatcher.matches("*", "anything"));
        assertTrue(HookMatcher.matches("  *  ", "anything"));
    }

    @Test
    void exactNameMatchesWholeStringOnly() {
        assertTrue(HookMatcher.matches("Bash", "Bash"));
        assertFalse(HookMatcher.matches("Bash", "Bash2"));
        assertFalse(HookMatcher.matches("Bash", "MegaBash"));
    }

    @Test
    void pipeAndCommaSeparateExactList() {
        assertTrue(HookMatcher.matches("Edit|Write", "Write"));
        assertTrue(HookMatcher.matches("Edit, Write", "Edit"));
        assertFalse(HookMatcher.matches("Edit|Write", "NotebookEdit"));
    }

    @Test
    void otherCharactersMeanUnanchoredRegex() {
        assertTrue(HookMatcher.matches("mcp__memory__.*", "mcp__memory__store"));
        assertTrue(HookMatcher.matches("^Edit$", "Edit"));
        assertFalse(HookMatcher.matches("^Edit$", "NotebookEdit"));
    }

    @Test
    void unanchoredRegexMatchesSubstring() {
        // Claude Code 文档明示的语义：Edit.* 也命中 NotebookEdit（未锚定 find）
        assertTrue(HookMatcher.matches("Edit.*", "NotebookEdit"));
    }

    @Test
    void invalidRegexMatchesNothingDefensively() {
        // 解析期已拦截非法正则（条目级跳过）；此为运行期防御兜底
        assertFalse(HookMatcher.matches("([unclosed", "anything"));
    }

    @Test
    void matchedCollectsAllHandlersOfMatchingRulesInOrder() {
        HookRule first = new HookRule("probe_tool",
                List.of(new HookHandler("a", List.of(), HookHandler.DEFAULT_TIMEOUT)));
        HookRule second = new HookRule("probe.*",
                List.of(new HookHandler("b", List.of(), HookHandler.DEFAULT_TIMEOUT),
                        new HookHandler("c", List.of(), HookHandler.DEFAULT_TIMEOUT)));
        HookRule miss = new HookRule("other", List.of(new HookHandler("d", List.of(),
                HookHandler.DEFAULT_TIMEOUT)));
        List<HookHandler> matched = HookMatcher.matched(List.of(first, second, miss), "probe_tool");
        assertEquals(3, matched.size());
        assertEquals(List.of("a", "b", "c"), matched.stream().map(HookHandler::command).toList(),
                "命中组按声明序展开处理器");
    }
}
