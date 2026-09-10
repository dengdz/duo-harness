package dev.duo.harness.tools.internal;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ApprovalDecision;
import dev.duo.harness.tools.ApprovalPolicyService;

import java.util.Set;

/**
 * 白名单放行策略：工具名在白名单内放行，否则拒绝。
 *
 * <p>白名单匹配工具的注册名（{@code mcp__<server>__<tool>} 或本地工具名），
 * 不做通配或正则——简单精确匹配。空白名单等价于恒拒。
 */
public final class AutoApprovePolicy implements ApprovalPolicyService {

    /** 策略来源标识（配置取值与审计署名共用）。 */
    public static final String SOURCE = "auto-approve";

    private final Set<String> allowedTools;

    /** @param allowedTools 放行白名单（复制持有；null 视为空集） */
    public AutoApprovePolicy(Set<String> allowedTools) {
        this.allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
    }

    @Override
    public ApprovalDecision decide(String toolName, JsonNode args) {
        if (allowedTools.contains(toolName)) {
            return ApprovalDecision.allow(SOURCE);
        }
        return ApprovalDecision.deny("不在审批白名单", SOURCE);
    }
}
