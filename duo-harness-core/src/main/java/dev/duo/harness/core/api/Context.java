package dev.duo.harness.core.api;

/**
 * 插件能看到的唯一句柄：子插件的挂载点、可逆副作用的注册点。
 * 一个 Context 实例对应一个插件实例的作用域，子插件获得子 Context。
 */
public interface Context {

    /** 创建根作用域：插件树的挂载起点。 */
    static Context root() {
        return new dev.duo.harness.core.internal.ContextImpl();
    }

    /**
     * 在本作用域下加载插件：内核把 rawConfig 绑定到插件声明的 config 类型后运行其 apply，
     * 失败（绑定或 apply 抛错）时同步抛出且不留任何残留副作用。
     *
     * <p>本方法同步阻塞直至 apply 返回（ADR-0002）。</p>
     *
     * @param rawConfig 原始配置（Map / JsonNode）；插件声明了 config 类型时必须提供
     *                  （否则绑定失败），无 config 类型时须为 {@code null}
     * @return 插件实例句柄；实例的销毁已注册为本作用域的副作用，随本作用域级联回滚
     * @throws NullPointerException plugin 为 null
     * @throws PluginConfigException rawConfig 无法绑定到 config 类型（点名插件与字段路径），
     *                               或声明了 config 类型却未提供配置
     * @throws PluginException 本作用域已销毁，或插件启动失败（cause 保留原始异常）
     */
    <C> PluginHandle plugin(Plugin<C> plugin, Object rawConfig);

    /**
     * 注册可逆副作用：本作用域销毁时按注册逆序执行。
     *
     * @return 幂等的移除器——手动调用等同提前回收，重复调用无副作用
     * @throws NullPointerException disposer 为 null
     * @throws PluginException 本作用域已销毁，拒绝注册（副作用不会静默丢失）
     */
    Disposable effect(Disposable disposer);

    /**
     * 销毁本作用域：逆序回滚全部副作用（含子插件实例）。幂等。
     *
     * @throws PluginException 聚合全部回滚错误（兄弟副作用的失败互不掩盖，见 suppressed）
     */
    void dispose();

    // === 服务 ===

    /**
     * 发布具名服务：登记进全局注册表（同一服务名在默认作用域内互斥，
     * 重复发布点名报错）。发布后依赖它的挂起插件被唤醒；注销时依赖它的
     * 活跃插件被停止。
     *
     * <p>注册表键为（服务名， 作用域）二阶结构；当前仅存在全局默认作用域，
     * 多作用域隔离能力预留。</p>
     *
     * @param name 服务名（全局扁平命名空间；harness 保留裸名，第三方加前缀）
     * @param instance 服务实例；类型契约由视图接口约定
     * @return 幂等注销器；发布同时是本作用域的副作用，随作用域销毁自动注销
     * @throws NullPointerException name 或 instance 为 null
     * @throws PluginException 本作用域已销毁，或同名服务已存在（点名先注册方）
     */
    Disposable provide(String name, Object instance);

    /**
     * 取得视图接口的动态代理：方法名即服务名，调用时惰性解析到注册表
     * 当前实例（服务替换后无需重新获取视图）。
     *
     * <p>类型窗口约定：视图方法名必须与注册的服务名一致，返回类型必须与
     * 服务实例类型兼容——错配在调用时点名报错。插件作用域内读取服务
     * 须先在 {@link Plugin#inject()} 声明对应服务名（根作用域无此要求）。</p>
     *
     * @param viewInterface 视图接口（必须是接口类型）
     * @return 视图代理；不触发解析，首次方法调用才寻址
     * @throws NullPointerException viewInterface 为 null
     * @throws PluginException viewInterface 不是接口
     */
    <T> T as(Class<T> viewInterface);

    // === 事件 ===

    /**
     * 注册普通事件监听器（emit / parallel / serial / bail 派发）。
     * 监听器表全树共享：任意作用域注册，任意作用域派发均可见。
     *
     * @return 幂等移除器；注册同时是本作用域的副作用，随作用域销毁自动摘除
     * @throws NullPointerException event 或 listener 为 null
     * @throws PluginException 本作用域已销毁
     */
    Disposable on(String event, EventListener listener);

    /**
     * 注册瀑布管线监听器（waterfall 派发），与普通监听器分表互不影响。
     * 注册序即洋葱层序：先注册者为最外层。
     *
     * <p><b>类型一致性约定</b>：监听器表存在泛型擦除，同一事件名的全部注册
     * 与全部派发必须使用一致的 T/R——错配不会在编译期暴露，
     * 而是在派发时以 ClassCastException（包装进 PluginException）失败。</p>
     *
     * @return 幂等移除器；随本作用域销毁自动摘除
     * @throws NullPointerException event 或 listener 为 null
     * @throws PluginException 本作用域已销毁
     */
    <T, R> Disposable on(String event, WaterfallListener<T, R> listener);

    /**
     * 广播：同步按注册序逐个调用，忽略返回值；监听器异常隔离
     * （记 warn 日志，不传染兄弟监听与派发方）。
     *
     * @throws NullPointerException event 为 null
     */
    void emit(String event, Object args);

    /**
     * 并发广播：每个监听器一个虚拟线程，等待全部完成；
     * 有失败则聚合抛出（首个为主异常，其余 suppressed）。
     *
     * @throws NullPointerException event 为 null
     * @throws PluginException 任一监听器失败（聚合），或等待线程被中断
     */
    void parallel(String event, Object args);

    /**
     * 顺序投票：按注册序调用，首个非 null 返回值即终值并终止链
     * （false 是合法投票值）；监听器异常包装后上抛派发方。
     *
     * @return 首个非 null 投票值；全员弃权返回 null
     * @throws NullPointerException event 为 null
     * @throws PluginException 监听器抛错时包装上抛（cause 保留原始异常）
     */
    Object serial(String event, Object args);

    /**
     * 同步投票：同步阻塞模型下与 {@link #serial} 语义等价，
     * 保留 DSH 词汇以对齐事件分派模式命名。
     *
     * @throws NullPointerException event 为 null
     * @throws PluginException 同 {@link #serial}
     */
    Object bail(String event, Object args);

    /**
     * 瀑布管线：监听器洋葱包裹终端默认行为，先注册者为最外层；
     * 监听器不调 next 即否决（内层与终端不执行）。
     *
     * <p>类型一致性约定同 {@link #on(String, WaterfallListener)}：
     * 派发的 T/R 必须与该事件名全部注册一致，错配在派发时失败。</p>
     *
     * @param args 初始载荷，逐层可改写
     * @param terminal 终端默认行为（无监听器拦截时执行）
     * @return 最外层监听器的返回值（或终端返回值）
     * @throws NullPointerException event 或 terminal 为 null
     * @throws PluginException 监听器或终端抛错时包装上抛（cause 保留原始异常）
     */
    <T, R> R waterfall(String event, T args, WaterfallNext<T, R> terminal);
}
