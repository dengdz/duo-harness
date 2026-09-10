package dev.duo.harness.mcp.internal;

import dev.duo.harness.tools.ToolsService;

/** tools 服务的视图接口（方法名即服务名，供 MCP 插件内部寻址）。 */
interface McpToolsView {

    ToolsService tools();
}
