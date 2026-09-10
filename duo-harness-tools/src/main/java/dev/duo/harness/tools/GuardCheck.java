package dev.duo.harness.tools;

/**
 * guard 单调否决检查：执行前的动态检查。
 *
 * <p>返回非 null 即拒绝（理由进入错误结果），返回 null 即放行。
 * 没有"允许"结果——guard 链顺序执行、首个拒绝立即短路，拒绝无法被
 * 任何后续 guard 或监听器翻回（单调性由结构保证）。</p>
 *
 * <p>与 pre-execute 监听器的区别：guard 是 ToolsService 上的注册 API
 * （{@code guard(registrant, check)}），时机在审批之后、工具本体之前；
 * pre-execute 监听器走事件表，时机在审批之前（审批的 ask 声明即出自它）。</p>
 */
@FunctionalInterface
public interface GuardCheck {

    /**
     * 检查本次工具执行。
     *
     * @param execution 执行载荷（工具名与参数可读）
     * @return 拒绝理由（非 null 即拒绝）；null 放行
     */
    String check(ToolExecution execution);
}
