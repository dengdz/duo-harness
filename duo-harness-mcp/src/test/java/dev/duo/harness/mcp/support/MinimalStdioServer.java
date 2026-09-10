package dev.duo.harness.mcp.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.PluginException;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试夹具：极简 stdio MCP 服务器（SDK server 侧构建，协议真实）。
 *
 * <p>启动模式由 args[0] 控制：</p>
 * <ul>
 *   <li>{@code normal} —— 注册 ping（回声）与 boom（返回 isError 结果）后常驻</li>
 *   <li>{@code grow} —— 先注册 ping，800ms 后动态补挂 late_tool
 *       （server 侧自动发出 tools/list_changed，演示变更重同步）</li>
 *   <li>{@code dup} —— 注册 {@code a.b} 与 {@code a$b}（命名清洗后同为 {@code a_b}）</li>
 *   <li>{@code once} —— args[1] 为标记文件：首次启动创建标记并常驻，
 *       其后每次启动立即退出（模拟"断连后再也连不上"，用于预算耗尽用例）</li>
 *   <li>{@code exit} —— 立即退出（用于重连预算/首连失败测试）</li>
 * </ul>
 *
 * <p>由测试经 {@code java -cp <test-classpath> 本类} 作为子进程启动。</p>
 */
public final class MinimalStdioServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "normal";
        if ("exit".equals(mode)) {
            System.exit(7);
        }
        if ("once".equals(mode) && !markAndClaim(args)) {
            System.exit(7);
        }
        var provider = new StdioServerTransportProvider(MAPPER);
        // 工具能力必须构建时声明——否则 addTool 抛 McpError（夹具启动即崩）
        McpSyncServer server = McpServer.sync(provider)
                .serverInfo("minimal-test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .build();
        if ("dup".equals(mode)) {
            server.addTool(fixedTool("a.b", "清洗后与 a$b 重名"));
            server.addTool(fixedTool("a$b", "清洗后与 a.b 重名"));
        } else {
            server.addTool(echoTool());
            server.addTool(boomTool());
            if ("grow".equals(mode)) {
                Thread.sleep(800);
                server.addTool(fixedTool("late_tool", "动态补挂的工具"));
            }
        }
        // stdio provider 在后台线程读 stdin；main 阻塞保活
        Thread.sleep(Long.MAX_VALUE);
    }

    /**
     * once 模式的标记抢占：首次启动创建标记返回 true，其后启动返回 false。
     * 由并发的启动尝试串行裁决（{@code CREATE_NEW} 的原子性是唯一判据）。
     */
    private static boolean markAndClaim(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("once 模式需要标记文件路径（args[1]）");
        }
        Path marker = Path.of(args[1]);
        try {
            Files.createFile(marker);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    /** 固定工具：回声（返回 "pong:" + input 参数）。 */
    private static McpServerFeatures.SyncToolSpecification echoTool() {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("ping", "回声工具", jsonSchema("""
                        {"type":"object","properties":{"input":{"type":"string"}},"required":["input"]}""")),
                (exchange, args) -> McpSchema.CallToolResult.builder()
                        .addTextContent("pong:" + args.getOrDefault("input", ""))
                        .build());
    }

    /** 错误工具：返回 isError 结果（验证错误形态的调用映射）。 */
    private static McpServerFeatures.SyncToolSpecification boomTool() {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("boom", "总是返回错误结果", jsonSchema(
                        "{\"type\":\"object\",\"properties\":{}}")),
                (exchange, args) -> McpSchema.CallToolResult.builder()
                        .addTextContent("远端拒绝执行")
                        .isError(true)
                        .build());
    }

    /** 无参数固定返回的工具。 */
    private static McpServerFeatures.SyncToolSpecification fixedTool(String name, String description) {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(name, description, jsonSchema("{\"type\":\"object\",\"properties\":{}}")),
                (exchange, args) -> McpSchema.CallToolResult.builder()
                        .addTextContent(name + " ok")
                        .build());
    }

    private static McpSchema.JsonSchema jsonSchema(String json) {
        try {
            return MAPPER.readValue(json, McpSchema.JsonSchema.class);
        } catch (Exception e) {
            throw new PluginException("夹具 schema 解析失败", e);
        }
    }
}
