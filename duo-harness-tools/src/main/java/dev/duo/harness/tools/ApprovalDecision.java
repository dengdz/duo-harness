package dev.duo.harness.tools;

/**
 * 审批决策：策略服务对一次工具调用的裁决结果。
 *
 * <p>三个组件：结果（放行/拒绝）、理由（拒绝时呈现在错误结果中）、策略来源
 *（安全审计的追溯锚点）。来源取值：预设策略署 {@code always-deny} /
 * {@code auto-approve}，无策略解析者时管线兜底署
 * {@link ApprovalPolicyService#SOURCE_UNCONFIGURED}。</p>
 *
 * @param outcome      放行或拒绝
 * @param reason       拒绝理由（放行时为空串）
 * @param policySource 策略来源标识
 */
public record ApprovalDecision(Outcome outcome, String reason, String policySource) {

    /** 审批结果。 */
    public enum Outcome { ALLOW, DENY }

    /** 放行决策。 */
    public static ApprovalDecision allow(String policySource) {
        return new ApprovalDecision(Outcome.ALLOW, "", policySource);
    }

    /** 拒绝决策。 */
    public static ApprovalDecision deny(String reason, String policySource) {
        return new ApprovalDecision(Outcome.DENY, reason, policySource);
    }
}
