package dev.duo.harness.core.api;

/**
 * 插件状态快照：某一时刻一个已挂载插件实例的只读视图（M8 状态面的数据源）。
 *
 * @param name  插件名——一律为插件类全限定名（yml 装载与编程挂载同此；yml 行的
 *              id 仅作装载行标识，不进快照名，BootYmlTest 按此事实断言）
 * @param state 挂载时刻的六态状态
 */
public record PluginSnapshot(String name, PluginState state) {

    /** 构造时校验非空——错误前移到构造点。 */
    public PluginSnapshot {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(state, "state");
    }
}
