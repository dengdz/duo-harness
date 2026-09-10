package dev.duo.harness.mcp.internal;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.tools.ToolsService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * MCP 插件的装配支撑：配置归一化 → serverName 占坑 → 首连（含工具同步）→
 * 挂生命周期 effect。public 供契约包的 McpClientPlugin 委托（静态工厂例外惯例）。
 */
public final class McpClientSupport {

    private McpClientSupport() {
    }

    /** 装配一个 MCP 连接：占坑 → 首连（含工具同步）→ 挂生命周期 effect。 */
    public static Disposable connect(Context ctx, JsonNode config) {
        McpConnectionOptions options = McpConnectionOptions.from(config);
        ToolsService tools = ctx.as(McpToolsView.class).tools();
        McpToolSync toolSync = new McpToolSync(options.serverName(), ctx, tools);
        // serverName 占坑：重复配置被服务注册表点名拒绝（先到先得）
        Disposable marker = ctx.provide("mcp-connection/" + options.serverName(), Boolean.TRUE);
        try {
            ConnectionSupervisor supervisor = new ConnectionSupervisor(options,
                    new ConnectionSupervisor.Listener() {
                        @Override
                        public void onConnected(McpSyncClient client) {
                            toolSync.sync(client);
                        }

                        @Override
                        public void onToolsChanged(List<McpSchema.Tool> tools) {
                            toolSync.onToolsChanged(tools);
                        }

                        @Override
                        public void onGaveUp() {
                            toolSync.unregisterAll();
                        }
                    });
            supervisor.runFirstAttempt();
            // 挂载与首连都可能抛（作用域并发销毁 / failOnStartupError）——统一回滚占坑
            Disposable lifecycle = ctx.effect(supervisor::stop);
            return () -> {
                lifecycle.dispose();
                marker.dispose();
            };
        } catch (RuntimeException e) {
            try {
                marker.dispose();
            } catch (Exception cleanup) {
                // 占坑补偿失败无碍：残留标记随插件停止注销（幂等）
            }
            throw e;
        }
    }
}
