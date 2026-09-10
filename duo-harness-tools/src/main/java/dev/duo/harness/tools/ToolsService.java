package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;

/**
 * "tools" 服务接口：工具注册与三段管线执行。经视图接口寻址
 * （方法名即服务名：{@code interface ToolsView { ToolsService tools(); }}）。
 *
 * <p>三段管线事件名（waterfall 监听器挂点，载荷均为 {@link ToolExecution}）：</p>
 * <ul>
 *   <li>{@value #PRE_EXECUTE}：准入段。监听器可 {@link ToolExecution#deny(String)}
 *       否决并不调 next（否决含默认放行）；亦可经
 *       {@link ToolExecution#requestApproval()} 声明本调用需审批（ask 三态），
 *       交由审批策略服务裁决——无策略解析者时按"未配置即拒"处理。</li>
 *   <li>{@value #EXECUTE}：本体段。around 终端即工具 execute；
 *       超时/重试类包装监听器挂此段。</li>
 *   <li>{@value #POST_EXECUTE}：结果治理段。监听器可改写结果或转错误形态。</li>
 * </ul>
 */
public interface ToolsService {

    /** 服务名（harness 保留裸名）。 */
    String SERVICE_NAME = "tools";

    /** 准入段事件名。 */
    String PRE_EXECUTE = "tools/pre-execute";

    /** 本体段事件名。 */
    String EXECUTE = "tools/execute";

    /** 结果治理段事件名。 */
    String POST_EXECUTE = "tools/post-execute";

    /**
     * 注册工具：同名冲突点名报错。
     *
     * <p>注册同时作为注册方作用域的 effect——注册方插件停止时工具自动注销
     * （注册即生命周期的硬契约，不依赖调用方记得处理返回值）。</p>
     *
     * @param registrant 注册方 Context（通常为插件 apply 的 ctx）
     * @return 幂等注销器（作用域销毁时已自动执行，一般无需手动调用）
     */
    Disposable register(Context registrant, ToolDefinition definition);

    /**
     * 注册 guard 单调否决检查：理由即拒绝、null 即放行，无"允许"结果，
     * 拒绝无法被翻回。时机在审批之后、工具本体之前。
     *
     * <p>注册即注册方作用域的 effect——注册方插件停止时 guard 自动摘除；
     * 生效范围是全部工具调用（含 MCP 远端工具），"仅作用域内调用生效"
     * 为已知限制（内核无调用方作用域概念）。</p>
     *
     * @param registrant 注册方 Context（通常为插件 apply 的 ctx）
     * @param check      检查逻辑（可注册多个，顺序执行、首个拒绝短路）
     * @return 幂等注销器（作用域销毁时已自动执行）
     */
    Disposable guard(Context registrant, GuardCheck check);

    /**
     * 经三段管线执行工具。
     *
     * @throws ToolNotFoundException 工具未注册（点名）
     */
    ToolResult execute(String toolName, JsonNode args);
}
