package dev.duo.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.PluginState;
import dev.duo.harness.mcp.support.MinimalStdioServer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内核级集成用例：McpClientPlugin 经 Context 装载——serverName 占坑（重复点名）、
 * failOnStartupError 两态、插件停止即断连。
 */
class McpClientPluginTest {

    private static JsonNode mcpConfig(String serverName, String mode, boolean failFast) {
        return JsonNodeFactory.instance.objectNode()
                .put("serverName", serverName)
                .put("command", javaCommand())
                .put("failOnStartupError", failFast)
                .set("args", JsonNodeFactory.instance.arrayNode()
                        .add("-cp").add(System.getProperty("java.class.path"))
                        .add(MinimalStdioServer.class.getName())
                        .add(mode));
    }

    @Test
    void duplicateServerNameIsRejected() {
        Context root = Context.root();
        PluginHandle first = root.plugin(new McpClientPlugin(), mcpConfig("dup", "normal", false));

        // 重复 serverName：第二个插件装载进 FAILED（provide 点名拒绝），错误经 awaitStartup 呈现
        PluginHandle dup = root.plugin(new McpClientPlugin(), mcpConfig("dup", "normal", false));
        assertEquals(PluginState.FAILED, dup.state());
        PluginException e = assertThrows(PluginException.class, dup::awaitStartup);
        // 点名消息在 cause 上（awaitStartup 重抛时包了启动失败外壳）
        assertTrue(String.valueOf(e.getCause()).contains("dup"),
                "应点名重复的服务名: " + e.getCause());

        first.dispose();
        dup.dispose();
    }

    @Test
    void failOnStartupErrorMarksPluginFailed() {
        Context root = Context.root();

        PluginHandle handle = root.plugin(new McpClientPlugin(),
                mcpConfig("doomed", "exit", true));

        // 装载不阻断：失败经 handle 呈现（六态模型，同内核语义）
        assertEquals(PluginState.FAILED, handle.state());
        PluginException e = assertThrows(PluginException.class, handle::awaitStartup);
        assertTrue(String.valueOf(e.getCause()).contains("failOnStartupError")
                || String.valueOf(e.getCause().getCause()).contains("failOnStartupError"));

        root.dispose();
    }

    @Test
    void pluginDisposeStopsConnection() {
        Context root = Context.root();
        PluginHandle handle = root.plugin(new McpClientPlugin(), mcpConfig("clean", "normal", false));
        handle.awaitStartup();
        assertEquals(PluginState.ACTIVE, handle.state());

        handle.dispose();

        assertEquals(PluginState.DISPOSED, handle.state());
        // 服务标记随插件注销：再次装载同名不冲突
        PluginHandle again = root.plugin(new McpClientPlugin(), mcpConfig("clean", "normal", false));
        again.awaitStartup();
        again.dispose();
        root.dispose();
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
