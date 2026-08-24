package dev.duo.harness.core.api;

/**
 * 插件实例句柄：一次插件加载的运行期实体。由 {@link Context#plugin} 创建，
 * 不直接构造。
 */
public interface PluginHandle {

    /**
     * 等待启动完成：依赖未就绪（PENDING）时阻塞至激活或失败
     * （虚拟线程友好，ADR-0002）；插件已失败则重抛原始错误（保留栈）。
     *
     * @throws PluginException 插件启动失败（cause 保留原始异常），或等待被中断
     */
    void awaitStartup();

    /** 销毁该插件实例（逆序回滚其全部副作用）。幂等。 */
    void dispose();
}
