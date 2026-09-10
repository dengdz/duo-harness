package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具定义：name / description / parameters 构成模型可见面
 * （M1 骨架不校验参数 schema；output 契约与并发分类等强化件属后续里程碑）。
 */
public interface ToolDefinition {

    /** 工具名（全局唯一注册键）。 */
    String name();

    /** 描述（模型可见）。 */
    String description();

    /** 参数 schema（JSON Schema 形态，模型可见；可为 NullNode）。 */
    JsonNode parameters();

    /**
     * 工具自身声明"每次调用需审批"（ask 三态）。
     *
     * <p>默认 false。返回 true 即本次执行进入审批流程：由审批策略服务的
     * 解析者（{@code tools/pre-execute} 监听器）裁决，未配置策略则拒绝。</p>
     */
    default boolean requiresApproval() {
        return false;
    }

    /**
     * 工具本体：在三段管线的 execute 段（around 终端）执行。
     *
     * @throws Exception 转为 error 结果（isError=true），不向调用方上抛——
     *                   工具执行错误是正常业务结果而非系统异常
     */
    Object execute(ToolExecution execution) throws Exception;
}
