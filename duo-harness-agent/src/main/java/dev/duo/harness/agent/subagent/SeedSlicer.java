package dev.duo.harness.agent.subagent;

import dev.duo.harness.session.SessionEvent;

import java.util.List;

/**
 * fork 播种的平衡前缀切点（ADR-0015 决策 4，DSH 同款）：父日志中"到最近完成轮
 * 末尾"的事件前缀。完成轮以 {@code assistant/message} 收尾——它只在 agent 循环
 * 正常给出最终回答时写入，其之前的日志必然轮轮平衡（每个 user 都有回复、
 * 每对 tool/call 与 tool/result 完整配对）；其之后的尾巴是进行中的半截轮
 * （LLM 失败的悬空 user、迭代上限的孤儿 tool/result），一律排除——播种半截轮
 * 会让子会话以无回复的悬空消息开局，投影即失衡。
 */
public final class SeedSlicer {

    private SeedSlicer() {
    }

    /**
     * 平衡完成轮前缀：事件 0 起到最后一个 {@code assistant/message}（含）。
     * 无完成轮（首轮尚未答完）返回空列表——无背景可继承，fork 自然退化为
     * 全新起点，不报错。
     */
    public static List<SessionEvent> balancedCompletedRounds(List<SessionEvent> events) {
        int lastCompleted = -1;
        for (int i = 0; i < events.size(); i++) {
            if (SessionEvent.ASSISTANT_MESSAGE.equals(events.get(i).type())) {
                lastCompleted = i;
            }
        }
        return lastCompleted < 0 ? List.of() : List.copyOf(events.subList(0, lastCompleted + 1));
    }
}
