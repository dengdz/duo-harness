package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 审批策略服务：裁决被声明的审批请求（ask）该放行还是拒绝。
 *
 * <p>pre-execute 的决策三态（allow / deny / ask）中，{@code ask} 委托本服务裁决。
 * 声明与裁决分离：{@link ToolDefinition#requiresApproval()} 或 pre-execute 监听器的
 * {@link ToolExecution#requestApproval()} 负责声明，本服务负责裁决；未被声明的调用
 * 不进入审批。两个预设实现：{@code always-deny}（缺省）与 {@code auto-approve}
 * （白名单），由 {@link ApprovalPlugin} 按配置选择并发布。</p>
 *
 * <p>经视图接口 {@link ApprovalPolicyView} 寻址（方法名即服务名）。</p>
 */
public interface ApprovalPolicyService {

    /** 服务名（harness 保留裸名）。 */
    String SERVICE_NAME = "approval";

    /** 策略来源标识：无策略解析者时的管线兜底（"未配置即拒"）。 */
    String SOURCE_UNCONFIGURED = "none";

    /**
     * 裁决一次被声明的工具调用。
     *
     * @param toolName 工具名
     * @param args     调用参数（策略可按参数内容裁决）
     * @return 审批决策（含结果、理由与策略来源）
     */
    ApprovalDecision decide(String toolName, JsonNode args);
}
