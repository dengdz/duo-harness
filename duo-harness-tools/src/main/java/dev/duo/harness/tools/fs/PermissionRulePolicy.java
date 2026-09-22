package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ApprovalDecision;
import dev.duo.harness.tools.ApprovalPolicyService;

import java.util.Optional;

/**
 * 规则前置裁决策略（M24，ADR-0026 决策一）：审批链最外层的规则装饰——命中即短路
 * （deny 拒绝、allow 放行，署名 {@link PermissionRules#SOURCE}），未命中委托内层链
 * （档位闸门 → 交互回答者）。放在最外层保证 deny 规则先于档位放行（deny 查全部），
 * bash 恒 ASK 的调用必达本层。
 *
 * <p>线程约定：规则表 volatile 读、内层策略无状态——可被工具循环并发调用。</p>
 */
public final class PermissionRulePolicy implements ApprovalPolicyService {

    private final PermissionRules rules;
    private final ApprovalPolicyService inner;

    public PermissionRulePolicy(PermissionRules rules, ApprovalPolicyService inner) {
        this.rules = java.util.Objects.requireNonNull(rules, "rules");
        this.inner = java.util.Objects.requireNonNull(inner, "inner");
    }

    @Override
    public ApprovalDecision decide(String toolName, JsonNode args) {
        return decide(toolName, args, null);
    }

    /** 携发起呈现位版：规则命中与标记无关，未命中委托内层时原样转发（亲和路由）。 */
    @Override
    public ApprovalDecision decide(String toolName, JsonNode args, String presenterId) {
        Optional<ApprovalDecision> verdict = rules.verdict(toolName, args);
        return verdict.orElseGet(() -> inner.decide(toolName, args, presenterId));
    }
}
