package dev.duo.harness.core.internal;

import dev.duo.harness.core.api.PluginHandle;

/**
 * 插件实例句柄：包装实例的私有作用域。
 * 同步加载模型下 handle 创建即启动完成；依赖驱动启停（后续工单）将赋予其实际等待语义。
 */
final class PluginHandleImpl implements PluginHandle {

    private final DefaultContext scope;

    PluginHandleImpl(DefaultContext scope) {
        this.scope = scope;
    }

    @Override
    public void awaitStartup() {
        // 同步模型下 plugin() 返回前 apply 已完成；失败路径不会产生 handle，
        // 此处为依赖驱动启停的异步推进保留语义位。
    }

    @Override
    public void dispose() {
        scope.dispose();
    }
}
