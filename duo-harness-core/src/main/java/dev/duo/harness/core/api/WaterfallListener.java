package dev.duo.harness.core.api;

/**
 * 瀑布管线监听器：洋葱模型的可否决拦截层。
 *
 * <p>收 (args, next)：可改写参数后调 next（传导给下一层）、可包装
 * next 的返回值、也可不调 next 直接返回替代值——后者即否决，
 * 内层监听器与终端默认行为都不再执行。</p>
 *
 * @param <T> 事件载荷类型
 * @param <R> 管线结果类型
 */
@FunctionalInterface
public interface WaterfallListener<T, R> {

    /**
     * 参与一层管线。
     *
     * @param args 外层传下来的载荷（可改写后经 next 下传）
     * @param next 调用即继续内层；参数为改写后的载荷
     * @return 本层结果（通常包装或透传 next 的返回值）
     * @throws Exception 直接上抛给派发方（否决请用返回值表达，不用异常）
     */
    R invoke(T args, WaterfallNext<T, R> next) throws Exception;
}
