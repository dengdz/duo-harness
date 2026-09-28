package dev.duo.harness.stats;

import dev.duo.harness.agent.commands.CommandsRegistry;
import dev.duo.harness.tools.ToolsService;

/**
 * 本插件消费的服务视图接口集合（方法名即服务名，ADR-0019 读取纪律）。
 * 独立成类便于测试侧经 {@code ctx.as(...)} 复用同一套视图。
 */
public final class StatsViews {

    private StatsViews() {
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    public interface ToolsView {

        ToolsService tools();
    }

    /** commands 服务的视图接口（方法名即服务名）。 */
    public interface CommandsView {

        CommandsRegistry commands();
    }
}
