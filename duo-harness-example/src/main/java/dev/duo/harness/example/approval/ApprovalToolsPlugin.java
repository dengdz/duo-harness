package dev.duo.harness.example.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.example.tools.ToolsView;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsService;

import java.util.Set;

/**
 * 审批演示用工具插件：注册三个工具，覆盖"声明需审批"与"不声明"两路。
 *
 * <ul>
 *   <li>{@code read_file} —— 声明需审批（白名单放行的候选）</li>
 *   <li>{@code delete_file} —— 声明需审批（白名单外的候选）</li>
 *   <li>{@code echo} —— 不声明，审批不应介入</li>
 * </ul>
 */
public final class ApprovalToolsPlugin implements Plugin<Void> {

    /** 声明需审批的读取工具。 */
    public static final String READ = "read_file";

    /** 声明需审批的删除工具。 */
    public static final String DELETE = "delete_file";

    /** 不声明需审批的工具。 */
    public static final String ECHO = "echo";

    @Override
    public Set<String> inject() {
        return Set.of(ToolsService.SERVICE_NAME);
    }

    @Override
    public Class<Void> configType() {
        return null;
    }

    @Override
    public Disposable apply(Context ctx, Void config) {
        ToolsService tools = ctx.as(ToolsView.class).tools();
        tools.register(ctx, tool(READ, true, "读取文件（需审批）"));
        tools.register(ctx, tool(DELETE, true, "删除文件（需审批）"));
        tools.register(ctx, tool(ECHO, false, "回声（不声明审批）"));
        return null;
    }

    private static ToolDefinition tool(String name, boolean needsApproval, String description) {
        return new ToolDefinition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public JsonNode parameters() {
                return NullNode.getInstance();
            }

            @Override
            public boolean requiresApproval() {
                return needsApproval;
            }

            @Override
            public Object execute(ToolExecution execution) {
                return name + " 已执行";
            }
        };
    }
}
