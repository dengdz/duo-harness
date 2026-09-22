package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ApprovalDecision;
import dev.duo.harness.tools.ApprovalPolicyService;

import java.util.Objects;
import java.util.Optional;

/**
 * 规则与只读前置裁决策略（M24 工单 01/03，ADR-0026 决策一/二）：审批链最外层的
 * 裁决序编排——① deny 规则恒优先（查全部命令，与 guard 单调否决同构）；② 只读
 * bash 判定命中即放行（署名 {@link ReadOnlyBashPolicy#SOURCE}）；③ allow 规则
 * （只查非只读命令——只读命令已被 ② 截获，allow 天然只达非只读面）；④ 未命中
 * 委托内层链（档位闸门 → 交互回答者）。放在最外层保证 deny 先于档位放行，bash
 * 恒 ASK 的调用必达本层。
 *
 * <p>线程约定：规则表与判定器只读、内层策略无状态——可被工具循环并发调用。</p>
 */
public final class PermissionRulePolicy implements ApprovalPolicyService {

    private final PermissionRules rules;
    private final ReadOnlyBashDetector detector;
    private final ApprovalPolicyService inner;

    public PermissionRulePolicy(PermissionRules rules, ReadOnlyBashDetector detector,
                                ApprovalPolicyService inner) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.inner = Objects.requireNonNull(inner, "inner");
    }

    @Override
    public ApprovalDecision decide(String toolName, JsonNode args) {
        return decide(toolName, args, null);
    }

    /** 携发起呈现位版：前三段裁决与标记无关，未命中委托内层时原样转发（亲和路由）。 */
    @Override
    public ApprovalDecision decide(String toolName, JsonNode args, String presenterId) {
        // ① deny 恒优先（查全部命令——只读命令也逃不出 deny）
        Optional<ApprovalDecision> deny = rules.denyVerdict(toolName, args);
        if (deny.isPresent()) {
            return deny.get();
        }
        // ② 只读免审批（ADR-0026 决策二）
        Optional<ApprovalDecision> readonly = ReadOnlyBashDetector.allowIfReadonly(detector, toolName, args);
        if (readonly.isPresent()) {
            return readonly.get();
        }
        // ③ allow 规则（只查非只读——只读已在 ② 截获）
        // ④ 未命中委托内层（档位 → 交互），亲和标记原样转发
        return rules.allowVerdict(toolName, args)
                .orElseGet(() -> inner.decide(toolName, args, presenterId));
    }
}
