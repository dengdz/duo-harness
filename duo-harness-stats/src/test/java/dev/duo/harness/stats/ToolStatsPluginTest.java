package dev.duo.harness.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.duo.harness.agent.commands.CommandDefinition;
import dev.duo.harness.agent.commands.CommandScope;
import dev.duo.harness.agent.commands.CommandsRegistry;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配接缝测试（外部行为面）：真实工具域 + 命令注册表在场时挂载插件，
 * 断言命令在册、工具在册、经公开 waterfall 派发后计数正确、查询工具
 * 回读 JSON。不经插件内部状态。
 */
class ToolStatsPluginTest {

    @Test
    void pluginRegistersCommandToolAndCounts() throws Exception {
        Context root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        root.provide(CommandsRegistry.SERVICE_NAME, new CommandsRegistry());
        root.plugin(new ToolStatsPlugin(), null).awaitStartup();

        // 命令在册：/toolstats，任意呈现位、执行中安全（纯内存只读）
        CommandsRegistry commands = root.as(StatsViews.CommandsView.class).commands();
        CommandDefinition command = commands.find(ToolStatsPlugin.COMMAND_NAME);
        assertNotNull(command, "/toolstats 命令应已注册");
        assertEquals(CommandScope.ANY, command.scope());
        assertTrue(command.busySafe());

        // 工具在册
        ToolsService tools = root.as(StatsViews.ToolsView.class).tools();
        assertTrue(tools.list().stream().anyMatch(t -> ToolStatsPlugin.TOOL_NAME.equals(t.name())),
                "tool_stats 查询工具应已注册");

        // 经公开 waterfall 派发两次成功 + 一次失败（模拟工具管线的真实派发形态）
        root.waterfall(ToolsService.POST_EXECUTE,
                new ToolExecution("read", NullNode.getInstance(), "cli"), e -> Boolean.TRUE);
        root.waterfall(ToolsService.POST_EXECUTE,
                new ToolExecution("read", NullNode.getInstance(), "web"), e -> Boolean.TRUE);
        ToolExecution failed = new ToolExecution("bash", NullNode.getInstance(), "cli");
        failed.markError("boom");
        root.waterfall(ToolsService.POST_EXECUTE, failed, e -> Boolean.TRUE);

        // 模型侧经 tool_stats 回读统计（外部可观察面）
        ToolDefinition statsTool = tools.list().stream()
                .filter(t -> ToolStatsPlugin.TOOL_NAME.equals(t.name()))
                .findFirst().orElseThrow();
        JsonNode json = new ObjectMapper()
                .readTree((String) statsTool.execute(new ToolExecution(ToolStatsPlugin.TOOL_NAME,
                        NullNode.getInstance())));
        assertEquals(2, json.get("usage").size());
        assertEquals("read", json.get("usage").get(0).get("tool").asText());
        assertEquals(2, json.get("usage").get(0).get("total").asLong());
        assertEquals(0, json.get("usage").get(0).get("failed").asLong());
        assertEquals("bash", json.get("usage").get(1).get("tool").asText());
        assertEquals(1, json.get("usage").get(1).get("failed").asLong());

        // 统计者也被统计：tool_stats 自身的查询调用同样计数（JavaDoc 与工具目录声明）
        root.waterfall(ToolsService.POST_EXECUTE,
                new ToolExecution(ToolStatsPlugin.TOOL_NAME, NullNode.getInstance()), e -> Boolean.TRUE);
        JsonNode selfJson = new ObjectMapper()
                .readTree((String) statsTool.execute(new ToolExecution(ToolStatsPlugin.TOOL_NAME,
                        NullNode.getInstance())));
        boolean selfCounted = false;
        for (JsonNode entry : selfJson.get("usage")) {
            if (ToolStatsPlugin.TOOL_NAME.equals(entry.get("tool").asText())) {
                selfCounted = entry.get("total").asLong() >= 1;
            }
        }
        assertTrue(selfCounted, "tool_stats 自身查询应被计数（统计者也被统计）");

        // 文本报表同一数据源（命令 handler 委托 toTable）
        assertTrue(((String) command.handler().apply(null)).contains("read: 2 次"));
    }
}
