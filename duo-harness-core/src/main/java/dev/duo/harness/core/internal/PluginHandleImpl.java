package dev.duo.harness.core.internal;

import dev.duo.harness.core.api.PluginHandle;

/**
 * 插件实例句柄：包装运行期实体。
 * awaitStartup 阻塞至依赖就绪并激活（PENDING 期间等待），失败重抛原始错误。
 */
final class PluginHandleImpl implements PluginHandle {

    /** 该插件实例的运行期实体；dispose 委托给它。 */
    private final PluginInstance instance;

    PluginHandleImpl(PluginInstance instance) {
        this.instance = instance;
    }

    @Override
    public void awaitStartup() {
        instance.await();
    }

    @Override
    public void dispose() {
        instance.dispose();
    }
}
