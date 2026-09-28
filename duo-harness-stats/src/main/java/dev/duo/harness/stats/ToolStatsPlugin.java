package dev.duo.harness.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.agent.commands.CommandDefinition;
import dev.duo.harness.agent.commands.CommandScope;
import dev.duo.harness.agent.commands.CommandsRegistry;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.events.WaterfallListener;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsService;

import java.util.Set;

/**
 * 工具统计插件（M27 demo，ADR-0029）：第三方插件形态范例——一个插件串起
 * 四个既有扩展点（事件监听 / 命令注册 / 工具注册 / 服务消费），全程零内核改动。
 *
 * <p>能力三件：监听 {@code tools/post-execute} 按工具名累计调用与成败；
 * 注册 {@code /toolstats} 命令现场出报表；注册 {@code tool_stats} 查询工具供
 * 模型侧查询。依赖声明 inject {@code tools} + {@code commands}（错误前移，
 * 缺服务不启动）；读取经视图接口（ADR-0019 纪律，见 {@link StatsViews}）。</p>
 *
 * <p>统计口径：计入进入执行段的调用（pre-execute 否决与审批挂起不经过结果
 * 治理段、不计）；{@code tool_stats} 自身的查询调用同样被计数——统计者也被
 * 统计。计数器并发安全（LongAdder），工具并行池多管线无争用问题。</p>
 */
public final class ToolStatsPlugin implements Plugin<Void> {

    /** 注册的模型侧查询工具名。 */
    public static final String TOOL_NAME = "tool_stats";

    /** 注册的命令名（呈现位输入 {@code /toolstats} 触发；注册名不带斜杠）。 */
    public static final String COMMAND_NAME = "toolstats";

    @Override
    public Set<String> inject() {
        return Set.of(ToolsService.SERVICE_NAME, CommandsRegistry.SERVICE_NAME);
    }

    @Override
    public Class<Void> configType() {
        return null;
    }

    @Override
    public Disposable apply(Context ctx, Void config) {
        ToolStats stats = new ToolStats();

        // 结果治理段计数（挂接即作用域 effect，插件停止自动摘除；只观察不改写）
        ctx.on(ToolsService.POST_EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    stats.record(exec.toolName(), exec.resultIsError());
                    return next.invoke(exec);
                });

        ToolsService tools = ctx.as(StatsViews.ToolsView.class).tools();
        tools.register(ctx, new ToolDefinition() {
            @Override
            public String name() {
                return TOOL_NAME;
            }

            @Override
            public String description() {
                return "工具使用统计查询：返回自插件挂载起各工具的调用次数与失败次数";
            }

            /** 零参数查询：schema 与实现诚实一致（无 properties）。 */
            @Override
            public JsonNode parameters() {
                var schema = JsonNodeFactory.instance.objectNode();
                schema.put("type", "object");
                schema.set("properties", JsonNodeFactory.instance.objectNode());
                return schema;
            }

            @Override
            public Object execute(ToolExecution execution) {
                return stats.toJson();
            }
        });

        CommandsRegistry commands = ctx.as(StatsViews.CommandsView.class).commands();
        // busySafe=true：纯内存只读，不触会话/上下文，agent 执行中可立即响应
        commands.register(ctx, new CommandDefinition(COMMAND_NAME,
                "工具使用统计：按工具名输出调用次数与失败次数",
                CommandScope.ANY, true, context -> stats.toTable()));
        return null;
    }
}
