package dev.duo.harness.core.api;

/**
 * 插件能看到的唯一句柄：子插件的挂载点、可逆副作用的注册点。
 * 一个 Context 实例对应一个插件实例的作用域，子插件获得子 Context。
 */
public interface Context {

    /** 创建根作用域：插件树的挂载起点。 */
    static Context root() {
        return new dev.duo.harness.core.internal.DefaultContext();
    }

    /**
     * 在本作用域下加载插件：内核把 rawConfig 绑定到插件声明的 config 类型后运行其 apply，
     * 失败（绑定或 apply 抛错）时同步抛出且不留任何残留副作用。
     *
     * <p>本方法同步阻塞直至 apply 返回（ADR-0002）。</p>
     *
     * @param rawConfig 原始配置（Map / JsonNode）；插件无 config 类型时须为 {@code null}
     * @return 插件实例句柄；实例的销毁已注册为本作用域的副作用，随本作用域级联回滚
     */
    <C> PluginHandle plugin(Plugin<C> plugin, Object rawConfig);

    /**
     * 注册可逆副作用：本作用域销毁时按注册逆序执行。
     *
     * @return 幂等的移除器——手动调用等同提前回收，重复调用无副作用
     */
    Disposable effect(Disposable disposer);

    /**
     * 销毁本作用域：逆序回滚全部副作用（含子插件实例）。幂等。
     *
     * @throws PluginException 聚合全部回滚错误（兄弟副作用的失败互不掩盖，见 suppressed）
     */
    void dispose();
}
