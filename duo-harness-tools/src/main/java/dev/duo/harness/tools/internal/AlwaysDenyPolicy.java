package dev.duo.harness.tools.internal;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ApprovalDecision;
import dev.duo.harness.tools.ApprovalPolicyService;

/**
 * 恒拒策略：所有被声明的审批请求一律拒绝。
 *
 * <p>缺省策略——"未配置即拒"的具象化：配置未指定策略、或压根只想要一道拒绝闸时使用。
 */
public final class AlwaysDenyPolicy implements ApprovalPolicyService {

    /** 策略来源标识（配置取值与审计署名共用）。 */
    public static final String SOURCE = "always-deny";

    @Override
    public ApprovalDecision decide(String toolName, JsonNode args) {
        return ApprovalDecision.deny("被审批策略拒绝", SOURCE);
    }
}
