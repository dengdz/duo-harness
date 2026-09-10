package dev.duo.harness.mcp.internal;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.mcp.McpClientPlugin;
import dev.duo.harness.mcp.support.MinimalStdioServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 C（MCP 连接）用例：真实 stdio 协议（SDK 双端）下的连接生命周期——
 * 首连、断连重连、预算耗尽、failOnStartupError 两态、serverName 占坑。
 */
class ConnectionLifecycleTest {

    private ConnectionSupervisor supervisor;

    @AfterEach
    void cleanup() throws Exception {
        if (supervisor != null) {
            supervisor.stop();
        }
        killServerProcesses();
    }

    // === 夹具 ===

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String classpath() {
        return System.getProperty("java.class.path");
    }

    private static McpConnectionOptions options(String mode, int maxAttempts, boolean failOnStartupError) {
        return new McpConnectionOptions("test-server", javaCommand(),
                List.of("-cp", classpath(), MinimalStdioServer.class.getName(), mode),
                Map.of(), failOnStartupError, true, 50, 300, maxAttempts, 5_000, 1_000);
    }

    /** 杀掉本测试产生的全部 server 子进程（触发断连）。 */
    private static void killServerProcesses() {
        ProcessHandle.current().descendants()
                .filter(ph -> ph.info().commandLine().orElse("").contains("MinimalStdioServer"))
                .forEach(ProcessHandle::destroy);
    }

    private static ConnectionSupervisor.State awaitState(ConnectionSupervisor s,
                                                         ConnectionSupervisor.State target)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (s.state() == target) {
                return target;
            }
            MILLISECONDS.sleep(50);
        }
        return s.state();
    }

    // === 用例 ===

    @Test
    void connectsThenStopsCleanly() throws Exception {
        supervisor = new ConnectionSupervisor(options("normal", 3, false));
        supervisor.runFirstAttempt();

        assertEquals(ConnectionSupervisor.State.CONNECTED, awaitState(supervisor,
            ConnectionSupervisor.State.CONNECTED));

        supervisor.stop();
        assertEquals(ConnectionSupervisor.State.STOPPED, supervisor.state());
        assertTrue(supervisor.firstFailure() == null, "成功连接不应记录失败");
    }

    @Test
    void processKillTriggersReconnectAndRecovery() throws Exception {
        supervisor = new ConnectionSupervisor(options("normal", 3, false));
        supervisor.runFirstAttempt();
        assertEquals(ConnectionSupervisor.State.CONNECTED, awaitState(supervisor,
            ConnectionSupervisor.State.CONNECTED));

        killServerProcesses();

        // 断连 → BACKOFF → 重连成功回到 CONNECTED（命令仍有效，进程被重新拉起）
        assertEquals(ConnectionSupervisor.State.CONNECTED, awaitState(supervisor,
            ConnectionSupervisor.State.CONNECTED));

        killServerProcesses();
        assertEquals(ConnectionSupervisor.State.CONNECTED, awaitState(supervisor, ConnectionSupervisor.State.CONNECTED),
                "二次断连仍可恢复");
    }

    @Test
    void immediateExitServerExhaustsBudget() throws Exception {
        // exit 模式 server 启动即退出 → initialize 失败 → 预算耗尽
        supervisor = new ConnectionSupervisor(options("exit", 2, false));
        supervisor.runFirstAttempt();

        assertEquals(ConnectionSupervisor.State.GAVE_UP, awaitState(supervisor, ConnectionSupervisor.State.GAVE_UP));
        assertTrue(supervisor.firstFailure() != null, "首连失败原因应被记录");
    }

    @Test
    void failOnStartupErrorSurfacesOnFirstAttempt() {
        ConnectionSupervisor failing = new ConnectionSupervisor(options("exit", 2, true));
        PluginException e = assertThrows(PluginException.class, failing::runFirstAttempt);
        assertTrue(e.getMessage().contains("failOnStartupError"), e.getMessage());
        assertEquals(ConnectionSupervisor.State.GAVE_UP, failing.state());
        failing.stop();
    }
}
