package dev.duo.harness.core.api;

/**
 * 插件实例句柄：一次插件加载的运行期实体。由 {@link Context#plugin} 创建，
 * 不直接构造。
 */
public interface PluginHandle {

    /**
     * 等待首次启动完成：依赖未就绪（PENDING）时阻塞至激活或失败
     * （虚拟线程友好，ADR-0002）；插件已失败则重抛原始错误（保留栈）。
     *
     * <p>只覆盖首次启动；运行期因依赖指纹变化发生的卸载/重启经
     * {@code plugin/status} 事件观测。</p>
     *
     * @throws PluginException 插件启动失败（cause 保留原始异常），或等待被中断
     */
    void awaitStartup();

    /**
     * 等待首次启动完成，最多等 {@code timeout} 时长（ADR-0019）：超时抛点名异常
     * （消息含缺失硬依赖清单），插件保持 PENDING——后台服务就绪照常激活，
     * 可再次等待。零时长等价"立即探测"（不等待，缺依赖即超时异常）。
     *
     * <p>只覆盖首次启动；运行期因依赖指纹变化发生的卸载/重启经
     * {@code plugin/status} 事件观测。</p>
     *
     * @param timeout 最长等待时长（非负）
     * @throws PluginException 等待超时（点名缺失服务）、插件启动失败（cause 保留原始异常），或等待被中断
     * @throws IllegalArgumentException timeout 为负
     */
    void awaitStartup(java.time.Duration timeout);

    /** 当前生命周期状态快照（六态见 {@link PluginState}）。 */
    PluginState state();

    /** 销毁该插件实例（逆序回滚其全部副作用）。幂等。 */
    void dispose();
}
