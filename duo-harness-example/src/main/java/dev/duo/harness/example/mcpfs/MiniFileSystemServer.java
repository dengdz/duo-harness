package dev.duo.harness.example.mcpfs;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.PluginException;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 迷你 filesystem MCP 服务器：SDK server 侧构建（真实 stdio 协议），不依赖外部运行时。
 *
 * <p>提供两个工具，根目录锁定在 args[0] 指定的目录（相对路径归一化后越界即拒；
 * 不解析符号链接——root 内的 symlink 可达根外，demo 场景不构造 symlink）：</p>
 * <ul>
 *   <li>{@code read_file(path)} —— 返回文本内容（isError 结果点名叫名）</li>
 *   <li>{@code write_file(path, content)} —— 写入文本并返回确认</li>
 * </ul>
 *
 * <p>由 demo 的 MCP 连接作为子进程拉起：
 * {@code java -cp <classpath> MiniFileSystemServer <rootDir>}。main 以
 * {@code sleep(MAX_VALUE)} 保活——断连靠进程被杀（父进程 dispose / 测试 cleanup）。</p>
 */
public final class MiniFileSystemServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final McpJsonMapper JSON = new JacksonMcpJsonMapper(MAPPER);

    private final Path root;

    private MiniFileSystemServer(Path root) {
        this.root = root;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: MiniFileSystemServer <rootDir>");
            System.exit(2);
        }
        MiniFileSystemServer server = new MiniFileSystemServer(Path.of(args[0]).toAbsolutePath()
                .normalize());
        Files.createDirectories(server.root);

        var provider = new StdioServerTransportProvider(JSON);
        McpSyncServer mcp = McpServer.sync(provider)
                .serverInfo("mini-filesystem", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .build();
        mcp.addTool(server.readTool());
        mcp.addTool(server.writeTool());
        // stdio provider 在后台线程读 stdin；main 阻塞保活
        Thread.sleep(Long.MAX_VALUE);
    }

    private McpServerFeatures.SyncToolSpecification readTool() {
        return new McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder()
                        .name("read_file")
                        .description("读取根目录下的文本文件（相对路径）")
                        .inputSchema(JSON, """
                                {"type":"object","properties":{"path":{"type":"string"}},\
                                "required":["path"]}""")
                        .build(),
                (exchange, args) -> {
                    String relative = args.getOrDefault("path", "").toString();
                    try {
                        String content = Files.readString(resolve(relative));
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(content)
                                .build();
                    } catch (Exception e) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("读取失败: " + e.getMessage())
                                .isError(true)
                                .build();
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification writeTool() {
        return new McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder()
                        .name("write_file")
                        .description("向根目录写入文本文件（相对路径）")
                        .inputSchema(JSON, """
                                {"type":"object","properties":{"path":{"type":"string"},\
                                "content":{"type":"string"}},"required":["path","content"]}""")
                        .build(),
                (exchange, args) -> {
                    String relative = args.getOrDefault("path", "").toString();
                    String content = args.getOrDefault("content", "").toString();
                    try {
                        Files.writeString(resolve(relative), content);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("已写入 " + relative + "（" + content.length() + " 字符）")
                                .build();
                    } catch (Exception e) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("写入失败: " + e.getMessage())
                                .isError(true)
                                .build();
                    }
                });
    }

    /** 相对路径归一化并强制留在根目录内（越界即拒）。 */
    private Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new PluginException("path 不能为空");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new PluginException("路径越界: " + relative);
        }
        return resolved;
    }
}
