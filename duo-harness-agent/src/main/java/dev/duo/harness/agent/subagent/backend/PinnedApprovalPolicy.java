package dev.duo.harness.agent.subagent.backend;

import dev.duo.harness.tools.ToolDefinition;

/**
 * 子代理审批钉死策略（M16 工单 03，limitations M15#3）：恒否——声明了需审批的
 * 工具调用确定性拒绝，拒绝理由作为工具结果回传子代理（DSH 形态：恒 never +
 * 理由回传）。子代理没有审批通道：交互请求无法到达子代理的上下文，挂起等待
 * 人工只会让子代理永久卡死。
 *
 * <p>设计为注入式策略对象而非视图内硬编码：装配处（{@code SubagentPlugin}）
 * 注入，后续要放宽（如按模板放行白名单）只换注入对象，视图层不感知。拒绝文案
 * 与工具域 DENY 文案同形（工具 "X" 执行被拒绝: 理由（策略: 来源）），并附把
 * 步骤交回父代理的指引——子代理在最终回答里转述限制，父 agent 据此接管。</p>
 */
public final class PinnedApprovalPolicy {

    /** 恒否实例（子代理审批钉死的唯一策略形态）。 */
    public static final PinnedApprovalPolicy ALWAYS_DENY = new PinnedApprovalPolicy();

    private PinnedApprovalPolicy() {
    }

    /** 是否放行该工具：声明了需审批（requiresApproval）即不放行，其余原样放行。 */
    public boolean allows(ToolDefinition definition) {
        return !definition.requiresApproval();
    }

    /** 标准格式拒绝理由：与工具域 DENY 文案同形，附交回父代理的指引。 */
    public String denialReason(String toolName) {
        return "工具 \"" + toolName + "\" 执行被拒绝: 需要用户审批，但子代理没有审批通道"
                + "（策略: 子代理审批钉死）。请把该步骤交回父代理处理，或改用无需审批的工具。";
    }
}
