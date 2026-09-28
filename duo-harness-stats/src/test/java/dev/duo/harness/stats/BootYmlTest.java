package dev.duo.harness.stats;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.PluginState;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * yml 装载路径测试（BUG-20260915-02 教训：Boot 装载路径不能零测试）：
 * 三行 yml（tools / commands / tool-stats）经 Boot.from 整树激活——
 * FQCN 反射装载、依赖声明、注册副作用全链路。
 */
class BootYmlTest {

    @Test
    void ymlRowsBootStatsPluginActive(@TempDir Path dir) throws Exception {
        Path yml = dir.resolve("agent-demo.yml");
        Files.writeString(yml, """
                plugins:
                  - id: tools
                    name: dev.duo.harness.tools.ToolsPlugin

                  - id: commands
                    name: dev.duo.harness.agent.commands.CommandsPlugin
                    config: {}

                  - id: tool-stats
                    name: dev.duo.harness.stats.ToolStatsPlugin
                """);

        Context root = Boot.from(yml);

        assertTrue(root.snapshots().stream().anyMatch(s ->
                        s.name().endsWith("ToolStatsPlugin") && s.state() == PluginState.ACTIVE),
                "tool-stats 行应经 yml 装载激活，实际快照: " + root.snapshots());
        assertNotNull(root.as(StatsViews.ToolsView.class).tools()
                .list().stream()
                .filter(t -> ToolStatsPlugin.TOOL_NAME.equals(t.name()))
                .findFirst().orElse(null), "yml 路径下 tool_stats 工具应已注册");
    }
}
