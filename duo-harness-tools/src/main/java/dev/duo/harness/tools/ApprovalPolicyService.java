package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 审批策略服务：裁决被声明的审批请求（ask）该放行还是拒绝。
 *
 * <p>pre-execute 的决策三态（allow / deny / ask）中，{@code ask} 委托本服务裁决。
 * 声明与裁决分离：{@link ToolDefinition#requiresApproval()} 或 pre-execute 监听器的
 * {@link ToolExecution#requestApproval()} 负责声明，本服务负责裁决；未被声明的调用
 * 不进入审批。三个预设实现：{@code always-deny}（缺省）、{@code auto-approve}
 * （白名单）与 {@code interactive}（委托交互 seam 由在场回答者作答，ADR-0008），
 * 由 {@link ApprovalPlugin} 按配置选择并发布。</p>
 *
 * <p>经视图接口 {@link ApprovalPolicyView} 寻址（方法名即服务名）。</p>
 */
public interface ApprovalPolicyService {

    /** 服务名（harness 保留裸名）。 */
    String SERVICE_NAME = "approval";

    /** 策略来源标识：无策略解析者时的管线兜底（"未配置即拒"）。 */
    String SOURCE_UNCONFIGURED = "none";

    /**
     * 裁决一次被声明的工具调用（无发起呈现位形态，C2 工单 12 方向翻转后的便捷
     * 缺省）：等价于 {@code decide(toolName, args, null)}。
     *
     * @param toolName 工具名
     * @param args     调用参数（策略可按参数内容裁决）
     * @return 审批决策（含结果、理由与策略来源）
     */
    default ApprovalDecision decide(String toolName, JsonNode args) {
        return decide(toolName, args, null);
    }

    /**
     * 裁决一次被声明的工具调用（携发起呈现位标记，M19 亲和路由 ADR-0020 决策 7；
     * C2 工单 12 升为抽象方法）：委托交互 seam 的策略（interactive 等）把标记带进
     * ask 请求——回答者路由据此发起方优先。此前「两参抽象 + 三参缺省忽略」是
     * M19 加参的过渡形态——过渡已结束，方向翻转后新实现不再可能静默丢弃标记；
     * 不关心标记的策略（always-deny / auto-approve）在实现里忽略参数即可。
     *
     * @param toolName    工具名
     * @param args        调用参数
     * @param presenterId 发起呈现位标记（直调/无发起方为 null）
     * @return 审批决策（含结果、理由与策略来源）
     */
    ApprovalDecision decide(String toolName, JsonNode args, String presenterId);
}
