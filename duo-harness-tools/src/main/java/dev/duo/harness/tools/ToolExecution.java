package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次工具执行的全程载荷：贯穿三段管线（pre-execute / execute / post-execute），
 * 各段监听器经它读参数、否决与改写结果。
 *
 * <p>可变性与线程约定：单次执行内串行传递，不跨线程共享。</p>
 */
public final class ToolExecution {

    /** 工具名（本次执行目标）。 */
    private final String toolName;
    /** 调用参数（NullNode 表示无参）。 */
    private final JsonNode args;

    /** pre-execute 否决理由；非 null 即已否决。 */
    private String denyReason;
    /** 执行结果；post-execute 段可改写。 */
    private Object result;
    /** 结果是否为错误形态。 */
    private boolean resultIsError;

    public ToolExecution(String toolName, JsonNode args) {
        this.toolName = toolName;
        this.args = args == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : args;
    }

    /** 工具名。 */
    public String toolName() {
        return toolName;
    }

    /** 调用参数（无参为 NullNode，不返回 null）。 */
    public JsonNode args() {
        return args;
    }

    /** pre-execute 监听器否决本次执行；理由将呈现在错误结果中。 */
    public void deny(String reason) {
        this.denyReason = reason;
    }

    /** 是否已被 pre-execute 否决。 */
    public boolean denied() {
        return denyReason != null;
    }

    /** 否决理由（未否决为 null）。 */
    public String denyReason() {
        return denyReason;
    }

    /** execute 段写入执行结果（正常形态）。 */
    public void setResult(Object value) {
        this.result = value;
        this.resultIsError = false;
    }

    /** 结果转为错误形态（execute 抛错或 post-execute 治理调用）。 */
    public void markError(String message) {
        this.result = message;
        this.resultIsError = true;
    }

    /** 当前结果值。 */
    public Object result() {
        return result;
    }

    /** 结果是否为错误形态。 */
    public boolean resultIsError() {
        return resultIsError;
    }
}
