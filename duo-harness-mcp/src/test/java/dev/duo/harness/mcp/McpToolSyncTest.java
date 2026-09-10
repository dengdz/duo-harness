package dev.duo.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.PluginState;
import dev.duo.harness.mcp.support.MinimalStdioServer;
import dev.duo.harness.tools.ToolNotFoundException;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 A（内核级端到端）用例：远端工具同步进工具域——出现、经三段管线执行、
 * 命名清洗冲突点名、tools/list_changed 重同步、断连保留与重连恢复、预算耗尽注销。
 *
 * <p>观测量只有两个：{@link ToolsService#execute} 的返回（工具在册且可调用）
 * 与 {@link ToolNotFoundException}（工具不在册）——工具域没有清单查询 API，
 * "在册"由可执行性定义。</p>
 */
class McpToolSyncTest {

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface ToolsView {

        ToolsService tools();
    }

    private Context root;
    private PluginHandle toolsHandle;

    @BeforeEach
    void setUp() {
        root = Context.root();
        toolsHandle = root.plugin(new ToolsPlugin(), null);
        toolsHandle.awaitStartup();
    }

    @AfterEach
    void cleanup() {
        killServerProcesses();
        root.dispose();
    }

    // === 夹具 ===

    private ToolsService tools() {
        return root.as(ToolsView.class).tools();
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** 装载一个 MCP 连接插件（reconnect 参数按用例定制）。 */
    private PluginHandle mount(String serverName, String mode, boolean failFast,
                               int maxAttempts, String... extraArgs) {
        var config = JsonNodeFactory.instance.objectNode()
                .put("serverName", serverName)
                .put("command", javaCommand())
                .put("failOnStartupError", failFast)
                // 断连窗口内的探活调用不能吊死用例：请求超时收到秒级
                .put("requestTimeoutMs", 2_000);
        var args = config.putArray("args").add("-cp").add(System.getProperty("java.class.path"))
                .add(MinimalStdioServer.class.getName()).add(mode);
        for (String extra : extraArgs) {
            args.add(extra);
        }
        config.putObject("reconnect")
                .put("initialDelayMs", 50)
                .put("maxDelayMs", 300)
                .put("maxAttempts", maxAttempts);
        return root.plugin(new McpClientPlugin(), config);
    }

    private static JsonNode input(String text) {
        return JsonNodeFactory.instance.objectNode().put("input", text);
    }

    /** 杀掉本测试产生的全部 server 子进程（触发断连）。 */
    private static void killServerProcesses() {
        ProcessHandle.current().descendants()
                .filter(ph -> ph.info().commandLine().orElse("").contains("MinimalStdioServer"))
                .forEach(ProcessHandle::destroy);
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            MILLISECONDS.sleep(50);
        }
        throw new AssertionError("等待超时：" + what);
    }

    /** 工具是否已在册（以"能执行"为判据）。 */
    private boolean registered(String toolName) {
        try {
            tools().execute(toolName, null);
            return true;
        } catch (ToolNotFoundException e) {
            return false;
        }
    }

    // === 用例 ===

    @Test
    void remoteToolsAppearAndExecuteThroughPipeline() throws Exception {
        PluginHandle mcp = mount("sync-test", "normal", false, 3);
        mcp.awaitStartup();
        assertEquals(PluginState.ACTIVE, mcp.state());

        // 远端工具以 mcp__<server>__<tool> 命名出现在工具域
        ToolResult echo = tools().execute("mcp__sync-test__ping", input("你好"));
        assertFalse(echo.isError(), String.valueOf(echo.value()));
        assertEquals("pong:你好", echo.value());

        // isError 结果收敛为 error 结果的错误形态（不上抛、不静默成功）
        ToolResult boom = tools().execute("mcp__sync-test__boom", null);
        assertTrue(boom.isError(), "远端 isError 应为错误形态");
        assertTrue(String.valueOf(boom.value()).contains("远端拒绝执行"), String.valueOf(boom.value()));

        mcp.dispose();
    }

    @Test
    void pluginStopUnregistersRemoteTools() throws Exception {
        PluginHandle mcp = mount("stop-test", "normal", false, 3);
        mcp.awaitStartup();
        assertTrue(registered("mcp__stop-test__ping"));

        mcp.dispose();

        assertFalse(registered("mcp__stop-test__ping"), "插件停止后远端工具应随作用域注销");
    }

    @Test
    void nameSanitizationCollisionFailsAndRegistersNothing() throws Exception {
        // a.b 与 a$b 清洗后同为 a_b：阶段一校验点名失败，注册表一个条目都不进
        PluginHandle mcp = mount("dup-test", "dup", true, 1);
        assertThrows(PluginException.class, mcp::awaitStartup);

        assertEquals(PluginState.FAILED, mcp.state());
        assertFalse(registered("mcp__dup-test__a_b"), "校验失败的批次不应留下任何注册");
        assertThrows(ToolNotFoundException.class, () -> tools().execute("mcp__dup-test__a_b", null));
    }

    @Test
    void listChangedNotificationResyncsTools() throws Exception {
        PluginHandle mcp = mount("grow-test", "grow", false, 3);
        mcp.awaitStartup();
        assertTrue(registered("mcp__grow-test__ping"));
        assertFalse(registered("mcp__grow-test__late_tool"), "补挂之前不应存在");

        // 夹具 800ms 后 addTool → server 侧发出 tools/list_changed → 自动重同步
        awaitTrue("late_tool 经 list_changed 重同步出现", () -> registered("mcp__grow-test__late_tool"));
        assertFalse(tools().execute("mcp__grow-test__late_tool", null).isError());

        mcp.dispose();
    }

    @Test
    void dropKeepsToolsUntilReconnectRefreshes() throws Exception {
        PluginHandle mcp = mount("drop-test", "normal", false, 3);
        mcp.awaitStartup();
        assertTrue(registered("mcp__drop-test__ping"));

        killServerProcesses();

        // 断连不撤工具（只有预算耗尽才注销）——重连期间旧代继续服务
        assertTrue(registered("mcp__drop-test__ping"), "断连不应立即注销工具");
        // 命令仍有效 → 重连成功 → 工具自动恢复
        awaitTrue("重连后 ping 可再次调用", () -> {
            try {
                return !tools().execute("mcp__drop-test__ping", input("回来了")).isError();
            } catch (RuntimeException e) {
                return false;
            }
        });

        mcp.dispose();
    }

    @Test
    void budgetExhaustionUnregistersTools(@TempDir Path tempDir) throws Exception {
        // once 模式：首个 server 进程常驻，其后每次启动都立即退出 → 重连必然失败
        Path marker = tempDir.resolve("served.once");
        PluginHandle mcp = mount("once-test", "once", false, 2, marker.toString());
        mcp.awaitStartup();
        assertTrue(registered("mcp__once-test__ping"));

        killServerProcesses();

        awaitTrue("预算耗尽后工具被注销", () -> !registered("mcp__once-test__ping"));
    }
}
