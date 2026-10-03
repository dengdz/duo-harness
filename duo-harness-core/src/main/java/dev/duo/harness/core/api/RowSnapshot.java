package dev.duo.harness.core.api;

import java.util.Objects;

/**
 * 装载行快照：某一时刻一行已装载插件的只读视图（行级控制的状态面，ADR-0037）。
 *
 * @param id         装载行 id（yml 行与运行期装载的稳定标识，区别于 {@link
 *                   PluginSnapshot} 的类名口径）
 * @param pluginName 插件类全限定名
 * @param state      快照时刻的六态状态（实时读取，非缓存）
 */
public record RowSnapshot(String id, String pluginName, PluginState state) {

    /** 构造时校验非空——错误前移到构造点。 */
    public RowSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(state, "state");
    }
}
