package dev.duo.harness.core.api.events;

import dev.duo.harness.core.api.Context;

/**
 * 瀑布管线的继续调用句柄：指向下一层监听器，或（最内层时）终端默认行为。
 *
 * <p>在 {@link Context#waterfall} 的第三个参数位置，本接口即终端本身——
 * 无监听器拦截时执行的默认行为。</p>
 *
 * @param <T> 载荷类型
 * @param <R> 结果类型
 */
@FunctionalInterface
public interface WaterfallNext<T, R> {

    /**
     * 继续内层执行。
     *
     * @param args 下传的载荷（监听器可改写）
     * @return 内层的返回值
     * @throws Exception 由内层上抛
     */
    R invoke(T args) throws Exception;
}
