package dev.duo.harness.tools;

/**
 * 工具执行结果：值 + 错误形态标记 + 结局枚举。否决与工具异常都表现为 error 结果
 * （不向调用方抛异常——错误是治理结果不是系统故障）。
 *
 * <p>outcome 结局枚举（M36 工单 01）：呈现层判读依据，替代前端文案嗅探——
 * {@code ok} 正常完成、{@code denied} 被拒绝（准入/审批/guard/plan 白名单/子代理
 * 策略，及工具自声明的业务拒绝如计划未获批准）、{@code failed} 执行失败（异常/
 * 超时/输出违约）。与 isError 正交：计划未获批准保持 isError = false（模型侧
 * 修订循环语义不变），仅 outcome 声明呈现语义。</p>
 *
 * @param value    结果值；错误形态下为错误消息
 * @param isError  是否错误形态
 * @param outcome  结局枚举（ok / denied / failed；兼容构造按 isError 归一）
 */
public record ToolResult(Object value, boolean isError, String outcome) {

    public static final String OUTCOME_OK = "ok";
    public static final String OUTCOME_DENIED = "denied";
    public static final String OUTCOME_FAILED = "failed";

    /** 兼容构造（M36 工单 01 前形态）：错误形态归 failed、正常归 ok。 */
    public ToolResult(Object value, boolean isError) {
        this(value, isError, isError ? OUTCOME_FAILED : OUTCOME_OK);
    }

    /** 正常结果。 */
    public static ToolResult of(Object value) {
        return new ToolResult(value, false, OUTCOME_OK);
    }

    /** 错误结果（执行失败语义——异常/超时/输出违约；治理否决用 {@link #denied}）。 */
    public static ToolResult error(String message) {
        return new ToolResult(message, true, OUTCOME_FAILED);
    }

    /** 拒绝结果（治理链否决：准入/审批/guard/plan 白名单/子代理策略——isError = true）。 */
    public static ToolResult denied(String message) {
        return new ToolResult(message, true, OUTCOME_DENIED);
    }
}
