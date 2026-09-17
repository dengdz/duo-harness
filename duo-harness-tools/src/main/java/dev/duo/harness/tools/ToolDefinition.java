package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具定义：name / description / parameters 构成模型可见面，
 * output / requiresApproval / isConcurrencySafe 构成执行治理面。
 */
public interface ToolDefinition {

    /** 工具名（全局唯一注册键）。 */
    String name();

    /** 描述（模型可见）。 */
    String description();

    /** 参数 schema（JSON Schema 形态，模型可见；可为 NullNode）。 */
    JsonNode parameters();

    /**
     * 输出契约（结果 JSON Schema；null 即未声明——宽松透传，不校验）。
     *
     * <p>声明即校验：执行结果经本契约校验，违约转 error 结果并点名违约原因
     * （与工具异常收敛同一出口）。MCP 远端声明 outputSchema 的工具经同步
     * 带入本声明，与本地工具同标准（双轨制）。</p>
     */
    default JsonNode output() {
        return null;
    }

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
     * 本工具的某次调用可否与其他工具调用同时执行（并发安全声明，ADR-0018）。
     *
     * <p>fail-closed：默认 false，判定抛错或返回非严格 true 一律按独占处理——
     * 宁可慢（退化为顺序执行），不可错（并发踩坏共享状态）。判定以本次调用
     * 参数为据；仅返回严格 true 且 {@link #requiresApproval()} 为 false 的调用
     * 才进入并行池（审批即独占）。</p>
     *
     * @param args 本次调用的参数（与 execute 收到的同一份）
     */
    default boolean isConcurrencySafe(JsonNode args) {
        return false;
    }

    /**
     * 本工具的某次调用是否豁免管线缺省超时（ADR-0018）。默认 false。
     *
     * <p>豁免面是"等待语义合理且时长不可预估"的工具——如 ask_user 等人回答。
     * 返回 true 时该调用不受 {@code tools/execute} 段超时监听器的限时约束。</p>
     *
     * @param args 本次调用的参数（与 execute 收到的同一份）
     */
    default boolean exemptFromPipelineTimeout(JsonNode args) {
        return false;
    }

    /**
     * 本工具的某次调用的管线超时上限覆盖（ADR-0018）。默认 null——用管线缺省。
     *
     * <p>自带协作式超时的工具（如 bash 的模型可传 timeoutMs）应覆写本方法，
     * 把上限放宽到协作式之上一档——协作式先到期、按工具自己的语义收场
     * （如杀进程树），管线超时只兜协作式之外的挂死。</p>
     *
     * @param args 本次调用的参数（与 execute 收到的同一份）
     * @return null = 用管线缺省；正值 = 本调用的超时上限（毫秒）
     */
    default Long pipelineTimeoutMs(JsonNode args) {
        return null;
    }

    /**
     * 工具本体：在三段管线的 execute 段（around 终端）执行。
     *
     * @throws Exception 转为 error 结果（isError=true），不向调用方上抛——
     *                   工具执行错误是正常业务结果而非系统异常
     */
    Object execute(ToolExecution execution) throws Exception;
}
