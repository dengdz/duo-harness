package dev.duo.harness.tools.internal;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.events.WaterfallListener;
import dev.duo.harness.tools.ApprovalPolicyService;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsService;

/**
 * 审批闸门：pre-execute 监听器（策略解析者）——两个审批插件共用的装配件。
 *
 * <p>监听器以 {@code next.invoke} 先行（around 语义）再裁决：内层治理者的
 * 否决与 ask 声明都已落定，且与自身注册次序无关。只裁决**被声明的 ask**
 * （{@link ToolDefinition#requiresApproval()} 或 pre-execute 监听器的
 * {@link ToolExecution#requestApproval()}）——声明归声明者，裁决归策略。</p>
 */
public final class ApprovalGate {

    private ApprovalGate() {
    }

    /** 策略解析监听器：内层先跑，内层否决或不涉审批则不介入，否则委托策略裁决。 */
    public static WaterfallListener<ToolExecution, Boolean> gateFor(ApprovalPolicyService policy) {
        return (exec, next) -> {
            Boolean inner = next.invoke(exec);
            if (!Boolean.TRUE.equals(inner) || exec.denied() || !exec.approvalRequested()) {
                // 未被声明需审批的调用不进入审批——策略只裁决 ask，不主动治理
                return inner;
            }
            // args 在内层之后取：内层监听器可能改写参数，策略应看到最终形态
            exec.resolveApproval(policy.decide(exec.toolName(), exec.args()));
            return inner;
        };
    }
}
