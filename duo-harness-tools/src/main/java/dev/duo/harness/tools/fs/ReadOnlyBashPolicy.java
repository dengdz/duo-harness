package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ApprovalDecision;
import dev.duo.harness.tools.ApprovalPolicyService;

import java.util.Objects;

/**
 * 只读免审批策略（M24 工单 03，ADR-0026 决策二）：bash 只读判定命中即放行
 * （署名 {@link #SOURCE}），未命中委托内层链。规则服务缺席时的独立形态——
 * 规则在场的完整裁决序（deny 查全部 → 只读放行 → allow 查非只读）由
 * {@link PermissionRulePolicy} 编排，本类不做 deny 检查（无规则服务即无 deny 来源）。
 *
 * <p>线程约定：判定器只读、内层策略无状态——可并发。</p>
 */
public final class ReadOnlyBashPolicy implements ApprovalPolicyService {

    /** 只读放行的审计署名（与档位/规则署名区分，决策日志可指认"只读免审"）。 */
    public static final String SOURCE = "read-only";

    private final ReadOnlyBashDetector detector;
    private final ApprovalPolicyService inner;

    public ReadOnlyBashPolicy(ReadOnlyBashDetector detector, ApprovalPolicyService inner) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.inner = Objects.requireNonNull(inner, "inner");
    }

    @Override
    public ApprovalDecision decide(String toolName, JsonNode args) {
        return decide(toolName, args, null);
    }

    /** 携发起呈现位版：只读命中与标记无关，未命中委托内层时原样转发（亲和路由）。 */
    @Override
    public ApprovalDecision decide(String toolName, JsonNode args, String presenterId) {
        return ReadOnlyBashDetector.allowIfReadonly(detector, toolName, args)
                .orElseGet(() -> inner.decide(toolName, args, presenterId));
    }
}
