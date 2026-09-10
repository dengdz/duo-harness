package dev.duo.harness.mcp.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;

/**
 * 测试夹具：极简 stdio MCP 服务器（SDK server 侧构建，协议真实）。
 *
 * <p>启动模式由 args[0] 控制：{@code normal} 常驻（等待 initialize/list 请求）；
 * {@code exit} 立即退出（用于重连预算/首连失败测试）。</p>
 *
 * <p>由测试经 {@code java -cp <test-classpath> 本类} 作为子进程启动。</p>
 */
public final class MinimalStdioServer {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "exit".equals(args[0])) {
            System.exit(7);
        }
        var provider = new StdioServerTransportProvider(new ObjectMapper());
        McpServer.sync(provider)
                .serverInfo("minimal-test-server", "1.0.0")
                .build();
        // stdio provider 在后台线程读 stdin；main 阻塞保活
        Thread.sleep(Long.MAX_VALUE);
    }

    private MinimalStdioServer() {
    }
}
