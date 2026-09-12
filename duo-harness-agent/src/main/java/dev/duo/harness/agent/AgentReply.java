package dev.duo.harness.agent;

import java.util.List;
import java.util.Objects;

/**
 * 一次 agent 任务的最终结果：循环结束（无更多工具调用 / 迭代上限 / 错误终止）后交付。
 *
 * @param finalText      最终回复文本（迭代上限触发时为错误说明）
 * @param toolInvocations 本次任务全部工具调用记录（按执行序；无工具轮为空列表）
 * @param completed      是否正常完成（false = 迭代上限触发等异常终止）
 */
public record AgentReply(String finalText, List<ToolInvocation> toolInvocations, boolean completed) {

    /** 构造时校验非空与防御性拷贝——错误前移到构造点。 */
    public AgentReply {
        Objects.requireNonNull(finalText, "finalText");
        Objects.requireNonNull(toolInvocations, "toolInvocations");
        toolInvocations = List.copyOf(toolInvocations);
    }
}
