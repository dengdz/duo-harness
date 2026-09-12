package dev.duo.harness.agent;

import java.util.Objects;

/**
 * 工具调用记录：agent 循环中一次工具执行的审计条目。
 *
 * @param tool          工具名（六段管线执行的入口名）
 * @param argumentsJson 调用参数（JSON 文本，原样记录）
 * @param result        执行结果文本（失败时为错误说明——管线已收敛为 error 结果）
 * @param isError       结果是否为错误形态
 */
public record ToolInvocation(String tool, String argumentsJson, String result, boolean isError) {

    /** 构造时校验非空——错误前移到构造点。 */
    public ToolInvocation {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        Objects.requireNonNull(result, "result");
    }
}
