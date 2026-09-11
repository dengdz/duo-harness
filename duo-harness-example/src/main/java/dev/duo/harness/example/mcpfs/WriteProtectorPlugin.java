package dev.duo.harness.example.mcpfs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.events.WaterfallListener;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsService;

import java.util.Set;

/**
 * 写保护治理插件（治理插件的示范形态）：对 MCP 文件服务器施加两条治理——
 *
 * <ul>
 *   <li>pre-execute 对 {@code write_file} 声明 ask（写操作需审批）——
 *       由审批策略服务裁决，demo 配 always-deny 即"写被审批拦截"；</li>
 *   <li>guard 对读 {@code secret} 文件单调拒绝（读取防线，无法被翻回）。</li>
 * </ul>
 *
 * <p>工具本体（远端 filesystem server）零改动——治理经事件总线与 guard API 施加。
 * 工具名前缀 {@code mcp__files__} 对应 demo 的 serverName=files（MCP 命名清洗规则）。</p>
 */
public final class WriteProtectorPlugin implements Plugin<Void> {

    /** guard 拦截的文件名关键字。 */
    static final String SECRET_KEYWORD = "secret";

    /** MCP 文件服务器的公开工具名前缀（mcp__&lt;serverName&gt;__）。 */
    static final String TOOL_PREFIX = "mcp__files__";

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
        ToolsService tools = ctx.as(WriteProtectorView.class).tools();
        Disposable ask = ctx.on(ToolsService.PRE_EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    if ((TOOL_PREFIX + "write_file").equals(exec.toolName())) {
                        exec.requestApproval();
                    }
                    return next.invoke(exec);
                });
        Disposable guard = tools.guard(ctx,
                exec -> exec.toolName().startsWith(TOOL_PREFIX + "read_file")
                        && exec.args().path("path").asText("").contains(SECRET_KEYWORD)
                        ? "禁止读取涉密文件" : null);
        ctx.emit(dev.duo.harness.example.DemoMain.DEMO_LOG_CHANNEL,
                "写保护就绪：write_file 需审批、涉密文件禁止读取");
        return () -> {
            guard.dispose();
            ask.dispose();
        };
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface WriteProtectorView {

        ToolsService tools();
    }
}
