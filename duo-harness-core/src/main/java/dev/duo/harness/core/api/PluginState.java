package dev.duo.harness.core.api;

/**
 * 插件实例的生命周期状态（六态）。
 *
 * <pre>
 * PENDING --依赖全就绪--> LOADING --apply 成功--> ACTIVE
 * LOADING --apply/绑定失败--> FAILED（终态）
 * ACTIVE --依赖指纹变化--> UNLOADING --回滚完成--> PENDING（回归等待，可自动重启）
 * 任意 --dispose--> UNLOADING --> DISPOSED（终态）
 * </pre>
 *
 * <p>UNLOADING 是瞬时迁移态，通常只在并发查询时可见。</p>
 */
public enum PluginState {

    /** 等待依赖：初始态，或依赖消失后回落（服务回归将自动重启）。 */
    PENDING,

    /** 加载中：apply 执行期间。 */
    LOADING,

    /** 激活：apply 已完成，副作用挂在其私有作用域上。 */
    ACTIVE,

    /** 失败：apply 或依赖解析抛错（终态，错误经 awaitStartup 重抛）。 */
    FAILED,

    /** 卸载中：回滚副作用期间（瞬时）。 */
    UNLOADING,

    /** 已销毁（终态）。 */
    DISPOSED
}
