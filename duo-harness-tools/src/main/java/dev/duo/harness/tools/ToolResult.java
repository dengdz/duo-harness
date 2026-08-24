package dev.duo.harness.tools;

/**
 * 工具执行结果：值 + 错误形态标记。否决与工具异常都表现为 error 结果
 * （不向调用方抛异常——错误是治理结果不是系统故障）。
 *
 * @param value 结果值；错误形态下为错误消息
 * @param isError 是否错误形态
 */
public record ToolResult(Object value, boolean isError) {

    /** 正常结果。 */
    public static ToolResult of(Object value) {
        return new ToolResult(value, false);
    }

    /** 错误结果。 */
    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }
}
