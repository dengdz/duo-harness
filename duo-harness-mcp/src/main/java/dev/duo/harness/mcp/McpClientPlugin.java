package dev.duo.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.tools.ToolsService;

import java.util.Set;

/**
 * MCP 客户端插件：一行配置连接一个 MCP 服务器（stdio）。
 *
 * <p>inject 声明 tools（远端工具注册的目标）；连接生命周期（首连/重连/
 * 预算耗尽）由内核插件语义承载：插件停止即断连。serverName 以标记服务
 * 注册（mcp-connection/&lt;serverName&gt;），重复配置在装载时被服务注册表
 * 点名拒绝。远端工具的同步注册见工具同步器。</p>
 *
 * <p>config 为可选密集型（重连参数等有文档化默认值），由本模块自绑定——
 * 内核的严格绑定服务于"必填契约"，此处省略即取默认。</p>
 */
public final class McpClientPlugin implements Plugin<JsonNode> {

    @Override
    public Set<String> inject() {
        return Set.of(ToolsService.SERVICE_NAME);
    }

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        return dev.duo.harness.mcp.internal.McpClientSupport.connect(ctx, config);
    }
}
