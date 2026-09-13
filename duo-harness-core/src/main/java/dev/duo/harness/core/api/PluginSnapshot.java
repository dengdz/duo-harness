package dev.duo.harness.core.api;

/**
 * 插件状态快照：某一时刻一个已挂载插件实例的只读视图（M8 状态面的数据源）。
 *
 * @param name  插件名（Boot 装载为 yml id；编程挂载为插件类简名）
 * @param state 挂载时刻的六态状态
 */
public record PluginSnapshot(String name, PluginState state) {

    /** 构造时校验非空——错误前移到构造点。 */
    public PluginSnapshot {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(state, "state");
    }
}
