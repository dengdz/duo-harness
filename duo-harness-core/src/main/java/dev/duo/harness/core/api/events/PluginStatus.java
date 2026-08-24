package dev.duo.harness.core.api.events;

import dev.duo.harness.core.api.PluginState;

/**
 * 插件实例状态迁移事件载荷：经 {@code ctx.emit(PluginStatus.EVENT, status)}
 * 广播，供 Demo 观测与后续状态页消费。
 *
 * @param plugin 插件类名（诊断显示名）
 * @param from 迁移前状态
 * @param to 迁移后状态
 */
public record PluginStatus(String plugin, PluginState from, PluginState to) {

    /** 状态迁移事件名（监听方与内核约定）。 */
    public static final String EVENT = "plugin/status";
}
