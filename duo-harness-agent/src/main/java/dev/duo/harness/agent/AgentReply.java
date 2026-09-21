package dev.duo.harness.agent;

import java.util.List;
import java.util.Objects;

/**
 * 一次 agent 任务的最终结果：循环结束（无更多工具调用 / 迭代上限 / 协作式中断 /
 * 错误终止）后交付。
 *
 * @param finalText      最终回复文本（迭代上限触发时为错误说明；中断时为中断说明）
 * @param toolInvocations 本次任务全部工具调用记录（按执行序；无工具轮为空列表）
 * @param completed      是否正常完成（false = 迭代上限 / 中断等异常终止）
 * @param interrupted    是否被协作式中断（M23 工单 02，ADR-0025 决策一——已流出文本
 *                       保留并打中断标记、未派发调用补合成结果、会话停在可恢复态）
 */
public record AgentReply(String finalText, List<ToolInvocation> toolInvocations, boolean completed,
                         boolean interrupted) {

    /** 兼容构造：非中断形态（interrupted = false）。 */
    public AgentReply(String finalText, List<ToolInvocation> toolInvocations, boolean completed) {
        this(finalText, toolInvocations, completed, false);
    }

    /** 构造时校验非空与防御性拷贝——错误前移到构造点。 */
    public AgentReply {
        Objects.requireNonNull(finalText, "finalText");
        Objects.requireNonNull(toolInvocations, "toolInvocations");
        toolInvocations = List.copyOf(toolInvocations);
    }
}
