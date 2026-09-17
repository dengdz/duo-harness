package dev.duo.harness.agent.subagent;

import dev.duo.harness.session.SessionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fork 播种切点用例（工单 03，ADR-0015 决策 4）：平衡完成轮前缀——切在最后一个
 * {@code assistant/message}（完成轮的收尾标志），进行中半截轮（悬空 user、孤儿
 * tool/result）一律排除；无完成轮即空前缀，fork 退化为全新起点。
 */
class SeedSlicerTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SeedSlicerTest —— fork 播种切点：完成轮全量、半截轮排除、"
                + "无完成轮空前缀、多轮切最近（4 用例） ===");
    }

    private static SessionEvent user(String text) {
        return new SessionEvent(SessionEvent.USER_MESSAGE, 0, text);
    }

    private static SessionEvent assistant(String text) {
        return new SessionEvent(SessionEvent.ASSISTANT_MESSAGE, 0, text);
    }

    private static SessionEvent toolRound(String id) {
        return new SessionEvent(SessionEvent.TOOL_CALL, 0, "{}", id, "fs_read");
    }

    @Test
    void completeRoundsSlicedAtLastAssistant() {
        List<SessionEvent> prefix = SeedSlicer.balancedCompletedRounds(List.of(
                user("第一问"), assistant("第一答"),
                user("第二问"), toolRound("c1"),
                new SessionEvent(SessionEvent.TOOL_RESULT, 0, "结果", "c1", "fs_read"),
                assistant("第二答")));

        assertEquals(6, prefix.size(), "全部事件都在完成轮内，前缀全量保留");
        assertEquals(SessionEvent.ASSISTANT_MESSAGE, prefix.get(5).type());
    }

    @Test
    void halfOpenRoundExcluded() {
        // 第二轮 LLM 已答后用户追问悬空（LLM 失败无回复）：悬空 user 不进播种
        List<SessionEvent> prefix = SeedSlicer.balancedCompletedRounds(List.of(
                user("第一问"), assistant("第一答"), user("悬空追问")));

        assertEquals(2, prefix.size(), "切到最后完成轮，悬空消息排除");
        assertEquals("第一答", prefix.get(1).text());
    }

    @Test
    void orphanToolResultExcluded() {
        // 迭代上限触发：尾部是孤儿 tool/result（无收尾 assistant）——半截轮不进播种
        List<SessionEvent> prefix = SeedSlicer.balancedCompletedRounds(List.of(
                user("第一问"), assistant("第一答"),
                user("第二问"), toolRound("c1"),
                new SessionEvent(SessionEvent.TOOL_RESULT, 0, "结果", "c1", "fs_read")));

        assertEquals(2, prefix.size(), "第二轮无收尾 assistant，整体排除");
        assertEquals("第一答", prefix.get(1).text());
    }

    @Test
    void noCompletedRoundYieldsEmptyPrefix() {
        assertTrue(SeedSlicer.balancedCompletedRounds(List.of()).isEmpty(), "空日志空前缀");
        assertTrue(SeedSlicer.balancedCompletedRounds(
                List.of(user("首轮未答"))).isEmpty(), "无完成轮空前缀——fork 退化为全新起点");
    }
}
