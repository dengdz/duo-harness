package dev.duo.harness.core.api.events;

/**
 * 普通事件监听器：emit / parallel / serial / bail 派发的统一形状。
 *
 * <p>返回值语义由派发模式决定：emit 与 parallel 忽略返回值；
 * serial 与 bail 把首个非 null 返回值视为终值并终止链（{@code false}
 * 是合法投票值，仅 null 表示"未投票"——与 DSH 的 isBailed 排除 false
 * 不同，Java 的 null 语义已足够干净）。</p>
 */
@FunctionalInterface
public interface EventListener {

    /**
     * 处理一次事件派发。
     *
     * @param args 事件载荷，形状由事件名的约定定义
     * @return emit/parallel 下无意义；serial/bail 下非 null 即终值
     * @throws Exception emit/parallel 下被隔离（不影响兄弟与派发方）；
     *                   serial/bail/waterfall 之外本接口不参与
     */
    Object on(Object args) throws Exception;
}
