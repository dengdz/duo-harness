package dev.duo.harness.mcp.internal;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.tools.ConnectorStatusBoard;
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

    /** 装配一个 MCP 连接：占坑 → 状态板注册 → 首连（含工具同步）→ 挂生命周期 effect。 */
    public static Disposable connect(Context ctx, JsonNode config) {
        McpConnectionOptions options = McpConnectionOptions.from(config);
        ToolsService tools = ctx.as(McpToolsView.class).tools();
        McpToolSync toolSync = new McpToolSync(options.serverName(), ctx, tools);
        // 连接器状态板（M24 工单 05，ADR-0026 决策四）：多连接行聚合同一块板——
        // 首行发布服务，行停止仅解绑生命周期（板为无资源单例，状态随重连覆盖）
        ConnectorStatusBoard board = ConnectorStatusBoard.shared();
        board.update(options.serverName(), "CONNECTING", "连接中");
        Disposable marker = ctx.provide("mcp-connection/" + options.serverName(), Boolean.TRUE);
        Disposable boardService = ctx.hasService(ConnectorStatusBoard.SERVICE_NAME)
                ? null : provideBoardService(ctx, board);
        try {
            ConnectionSupervisor supervisor = new ConnectionSupervisor(options,
                    new ConnectionSupervisor.Listener() {
                        @Override
                        public void onConnected(McpSyncClient client) {
                            toolSync.sync(client);
                            board.update(options.serverName(), "CONNECTED", "已连接");
                        }

                        @Override
                        public void onToolsChanged(List<McpSchema.Tool> tools) {
                            toolSync.onToolsChanged(tools);
                        }

                        @Override
                        public void onGaveUp() {
                            toolSync.unregisterAll();
                            board.update(options.serverName(), "GAVE_UP", "重连预算耗尽，工具已下线");
                            String notice = "MCP 服务器 [" + options.serverName()
                                    + "] 重连预算耗尽，相关工具已下线";
                            board.fireGaveUp(notice);
                        }
                    });
            supervisor.runFirstAttempt();
            // 挂载与首连都可能抛（作用域并发销毁 / failOnStartupError）——统一回滚占坑；
            // 行停止/拔线时同步移除状态板条目（防幽灵"连接中/已下线"条目永驻）
            Disposable lifecycle = ctx.effect(() -> {
                supervisor.stop();
                board.remove(options.serverName());
            });
            return () -> {
                lifecycle.dispose();
                marker.dispose();
                if (boardService != null) {
                    boardService.dispose();
                }
            };
        } catch (RuntimeException e) {
            board.remove(options.serverName()); // 占坑失败的 CONNECTING 幽灵条目一并清理
            try {
                marker.dispose();
                if (boardService != null) {
                    boardService.dispose();
                }
            } catch (Exception cleanup) {
                // 占坑补偿失败无碍：残留标记随插件停止注销（幂等）
            }
            throw e;
        }
    }

    /**
     * 状态板服务发布（幂等兜底）：多连接行理论上由 Boot 串行装配不会竞态，
     * 此处对「已发布再 provide」的重复注册吞掉异常按复用处理（防埋雷）。
     */
    private static Disposable provideBoardService(Context ctx, ConnectorStatusBoard board) {
        try {
            return ctx.provide(ConnectorStatusBoard.SERVICE_NAME, board);
        } catch (RuntimeException e) {
            return () -> { }; // 已被首行发布：按复用处理
        }
    }
}
