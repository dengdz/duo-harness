package dev.duo.harness.core.api;

/**
 * 插件实例句柄：一次插件加载的运行期实体。由 {@link Context#plugin} 创建，
 * 不直接构造。
 */
public interface PluginHandle {

    /**
     * 等待启动完成。当前内核为同步加载，本调用立即返回；
     * 若插件已失败则重抛原始错误（保留栈）。
     * 为依赖驱动启停（后续工单）保留异步语义位。
     */
    void awaitStartup();

    /** 销毁该插件实例（逆序回滚其全部副作用）。幂等。 */
    void dispose();
}
