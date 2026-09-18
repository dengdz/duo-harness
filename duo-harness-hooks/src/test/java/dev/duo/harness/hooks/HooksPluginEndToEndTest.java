package dev.duo.harness.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginState;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.core.api.boot.BootException;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * hooks 插件端到端用例（接缝 A，boot 全链路 + 真外部进程）：yml 挂 hooks 插件行 +
 * 临时 DuoHome 的 hooks.json + 系统命令作钩子 → 经 ToolsService.execute 断言——
 * exit 2 阻断匹配工具（stderr 回给结果）、fail-open（脚本不存在放行）、空配置空转、
 * 坏 JSON 插件 FAILED 点名文件（boot 审计不连坐）、不支持事件跳过后其余照常。
 */
class HooksPluginEndToEndTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：HooksPluginEndToEndTest —— hooks 端到端（真进程）：exit 2 阻断、"
                + "Pre/Post 阻断与改写、JSON 裁定三形、fail-open、超时放行、载荷送达、matcher 多选正则、"
                + "空转、坏配置点名、事件跳过、Post 段 JSON block（14 用例） ===");
    }

    @TempDir
    Path tempDir;

    private String previousHome;

    @BeforeEach
    void injectTempDuoHome() {
        previousHome = System.getProperty(DuoHome.PROP_OVERRIDE);
        System.setProperty(DuoHome.PROP_OVERRIDE, tempDir.resolve("home").toString());
    }

    @AfterEach
    void restoreDuoHome() {
        if (previousHome == null) {
            System.clearProperty(DuoHome.PROP_OVERRIDE);
        } else {
            System.setProperty(DuoHome.PROP_OVERRIDE, previousHome);
        }
    }

    /** 测试工具插件：注册 probe_tool 与 plain_tool 两件即退工具（返回标记串）。 */
    public static class ProbeToolsPlugin implements Plugin<Void> {

        interface ToolsView {

            ToolsService tools();
        }

        @Override
        public java.util.Set<String> inject() {
            return java.util.Set.of(ToolsService.SERVICE_NAME);
        }

        @Override
        public Class<Void> configType() {
            return null;
        }

        /** probe_tool 的执行计数（PostToolUse"工具已执行、仅结果改写"的实证面）。 */
        static final java.util.concurrent.atomic.AtomicInteger EXECUTIONS =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Disposable apply(Context ctx, Void config) {
            ToolsService tools = ctx.as(ToolsView.class).tools();
            tools.register(ctx, simpleTool("probe_tool", "probe-ok", true));
            tools.register(ctx, simpleTool("plain_tool", "plain-ok", false));
            return null;
        }

        private ToolDefinition simpleTool(String name, String reply, boolean counted) {
            return new ToolDefinition() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public String description() {
                    return "端到端测试工具";
                }

                @Override
                public JsonNode parameters() {
                    return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                            .objectNode().put("type", "object");
                }

                @Override
                public Object execute(ToolExecution execution) {
                    if (counted) {
                        EXECUTIONS.incrementAndGet();
                    }
                    return reply;
                }
            };
        }
    }

    private Context boot() throws java.io.IOException {
        Path yml = tempDir.resolve("hooks-e2e.yml");
        Files.writeString(yml, """
                plugins:
                  - id: tools
                    name: dev.duo.harness.tools.ToolsPlugin
                  - id: hooks
                    name: dev.duo.harness.hooks.HooksPlugin
                  - id: probe
                    name: %s
                """.formatted(ProbeToolsPlugin.class.getName()));
        return Boot.from(yml);
    }

    private Path writeHooksConfig(String json) throws Exception {
        Path file = tempDir.resolve("home").resolve(HooksPlugin.CONFIG_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
        return file;
    }

    private ToolResult execute(Context root, String toolName) {
        ToolResult result = root.as(ProbeToolsPlugin.ToolsView.class).tools()
                .execute(toolName, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode());
        return result;
    }

    @Test
    void exitTwoDeniesMatchingToolOnly() throws Exception {
        writeHooksConfig("""
                {"hooks": {"PreToolUse": [
                  {"matcher": "probe_tool", "hooks": [{"type": "command", "command": "echo 钩子拦截 >&2; exit 2"}]}
                ]}}
                """);
        Context root = boot();
        try {
            ToolResult denied = execute(root, "probe_tool");
            assertTrue(denied.isError(), "exit 2 应阻断匹配工具");
            assertTrue(denied.value().toString().contains("被 PreToolUse 钩子阻断")
                            && denied.value().toString().contains("钩子拦截"),
                    "拒绝结果应含 stderr: " + denied.value());
            ToolResult allowed = execute(root, "plain_tool");
            assertFalse(allowed.isError(), "不匹配 matcher 的工具不受影响");
            assertEquals("plain-ok", allowed.value());
        } finally {
            root.dispose();
        }
    }

    @Test
    void missingHookScriptFailsOpen() throws Exception {
        // fail-open（ADR-0019 决策 2）：脚本不存在（sh -c 127 非阻断面）→ 调用照常
        writeHooksConfig("""
                {"hooks": {"PreToolUse": [
                  {"matcher": "*", "hooks": [{"type": "command", "command": "/nonexistent-hook-xyz"}]}
                ]}}
                """);
        Context root = boot();
        try {
            ToolResult result = execute(root, "probe_tool");
            assertFalse(result.isError(), "钩子起不来（非阻断退出）应放行: " + result.value());
            assertEquals("probe-ok", result.value());
        } finally {
            root.dispose();
        }
    }

    @Test
    void missingConfigFileIdlesActive() throws Exception {
        // 不写 hooks.json：插件 ACTIVE 空转，工具照常执行
        Context root = boot();
        try {
            assertEquals(PluginState.ACTIVE, stateOf(root, "dev.duo.harness.hooks.HooksPlugin"));
            ToolResult result = execute(root, "probe_tool");
            assertEquals("probe-ok", result.value());
        } finally {
            root.dispose();
        }
    }

    @Test
    void brokenJsonFailsPluginNamingFile() throws Exception {
        // 结构错误 → hooks 插件 FAILED、boot 审计点名文件，其他行不受累（不连坐）
        writeHooksConfig("{not json");
        BootException e = assertThrows(BootException.class, this::boot);
        assertTrue(e.getMessage().contains("hooks 配置解析失败"), e.getMessage());
        assertTrue(e.getMessage().contains(HooksPlugin.CONFIG_FILE), e.getMessage());
        assertTrue(e.getMessage().contains("hooks] 启动失败"), "点名 hooks 行: " + e.getMessage());
        assertTrue(!e.getMessage().contains("probe] 启动失败"), "探针行不受累: " + e.getMessage());
    }

    @Test
    void unsupportedEventSkippedWhilePreToolUseWorks() throws Exception {
        // 事件跳过（WARN）不炸启动：SessionStart 跳过，PreToolUse 照常阻断
        writeHooksConfig("""
                {"hooks": {
                  "SessionStart": [{"command": "true"}],
                  "PreToolUse": [{"matcher": "*", "hooks": [{"type": "command", "command": "exit 2"}]}]
                }}
                """);
        Context root = boot();
        try {
            ToolResult result = execute(root, "probe_tool");
            assertTrue(result.isError(), "受支持事件照常生效");
        } finally {
            root.dispose();
        }
    }

    @Test
    void postToolUseExitTwoRewritesResultToError() throws Exception {
        // PostToolUse 的"阻断" = 结果改写为错误回给模型；工具本体确实已执行（audit-only）
        ProbeToolsPlugin.EXECUTIONS.set(0);
        writeHooksConfig("""
                {"hooks": {"PostToolUse": [
                  {"matcher": "*", "hooks": [{"type": "command", "command": "echo 审计拦截 >&2; exit 2"}]}
                ]}}
                """);
        Context root = boot();
        try {
            ToolResult result = execute(root, "probe_tool");
            assertTrue(result.isError(), "PostToolUse exit 2 应把结果改写为错误: " + result.value());
            assertTrue(result.value().toString().contains("被 PostToolUse 钩子阻断")
                            && result.value().toString().contains("审计拦截"),
                    "错误结果应含 stderr: " + result.value());
            assertEquals(1, ProbeToolsPlugin.EXECUTIONS.get(), "工具本体已执行（不假装撤销副作用）");
        } finally {
            root.dispose();
        }
    }

    @Test
    void postToolUseJsonBlockRewritesResult() throws Exception {
        // PostToolUse exit 0 的 legacy JSON block 与 exit 2 同义：结果改写为错误
        ProbeToolsPlugin.EXECUTIONS.set(0);
        writeHooksConfig("""
                {"hooks": {"PostToolUse": [
                  {"matcher": "probe_tool", "hooks": [{"type": "command",
                    "command": "echo '{\\\"decision\\\": \\\"block\\\", \\\"reason\\\": \\\"post 否决\\\"}'"}]}
                ]}}
                """);
        Context root = boot();
        try {
            ToolResult result = execute(root, "probe_tool");
            assertTrue(result.isError() && result.value().toString().contains("post 否决"),
                    "Post 段 legacy block 应生效: " + result.value());
            assertEquals(1, ProbeToolsPlugin.EXECUTIONS.get(), "本体已执行");
        } finally {
            root.dispose();
        }
    }

    @Test
    void preToolUseJsonDecisionDenyShowsReason() throws Exception {
        // stdout JSON 裁定（扁平 permissionDecision 形）：deny 生效、reason 呈现给模型
        writeHooksConfig("""
                {"hooks": {"PreToolUse": [
                  {"matcher": "probe_tool", "hooks": [{"type": "command",
                    "command": "echo '{\\\"permissionDecision\\\": \\\"deny\\\", \\\"permissionDecisionReason\\\": \\\"JSON 否决\\\"}'"}]}
                ]}}
                """);
        Context root = boot();
        try {
            ToolResult result = execute(root, "probe_tool");
            assertTrue(result.isError() && result.value().toString().contains("JSON 否决"),
                    "JSON deny 应生效且 reason 呈现: " + result.value());
        } finally {
            root.dispose();
        }
    }

    @Test
    void legacyDecisionShapeHonored() throws Exception {
        // legacy 形 {decision: block, reason}：block 等价 deny
        writeHooksConfig("""
                {"hooks": {"PreToolUse": [
                  {"matcher": "probe_tool", "hooks": [{"type": "command",
                    "command": "echo '{\\\"decision\\\": \\\"block\\\", \\\"reason\\\": \\\"legacy 否决\\\"}'"}]}
                ]}}
                """);
        Context root = boot();
        try {
            ToolResult result = execute(root, "probe_tool");
            assertTrue(result.isError() && result.value().toString().contains("legacy 否决"),
                    "legacy block 形应生效: " + result.value());
        } finally {
            root.dispose();
        }
    }

    @Test
    void jsonAllowProceeds() throws Exception {
        // allow 与放行等价（不带改写）
        writeHooksConfig("""
                {"hooks": {"PreToolUse": [
                  {"matcher": "*", "hooks": [{"type": "command",
                    "command": "echo '{\\\"permissionDecision\\\": \\\"allow\\\"}'"}]}
                ]}}
                """);
        Context root = boot();
        try {
            ToolResult result = execute(root, "probe_tool");
            assertFalse(result.isError(), "allow 应放行: " + result.value());
            assertEquals("probe-ok", result.value());
        } finally {
            root.dispose();
        }
    }

    @Test
    void malformedJsonStdoutProceedsNonBlocking() throws Exception {
        // stdout 以 { 开头但非法 JSON = 非阻断错误（Claude Code 同款），调用继续
        writeHooksConfig("""
                {"hooks": {"PreToolUse": [
                  {"matcher": "*", "hooks": [{"type": "command", "command": "echo '{broken'"}]}
                ]}}
                """);
        Context root = boot();
        try {
            ToolResult result = execute(root, "probe_tool");
            assertFalse(result.isError(), "非法 JSON stdout 应放行: " + result.value());
            assertEquals("probe-ok", result.value());
        } finally {
            root.dispose();
        }
    }

    @Test
    void timeoutEntryCancelsAndFailsOpen() throws Exception {
        // 条目级 timeout（秒，缺省 600s）：超时取消进程、丢弃输出、放行 + WARN
        writeHooksConfig("""
                {"hooks": {"PreToolUse": [
                  {"matcher": "*", "hooks": [{"type": "command", "command": "sleep 30", "timeout": 1}]}
                ]}}
                """);
        Context root = boot();
        try {
            ToolResult result = execute(root, "probe_tool");
            assertFalse(result.isError(), "钩子超时应放行（fail-open）: " + result.value());
            assertEquals("probe-ok", result.value());
        } finally {
            root.dispose();
        }
    }

    @Test
    void payloadCarriesDeclaredFieldsViaStdin() throws Exception {
        // 载荷经 stdin 送达且含一期声明字段：钩子 cat 落盘后 exit 2（确证钩子执行过）
        writeHooksConfig("""
                {"hooks": {"PreToolUse": [
                  {"matcher": "probe_tool", "hooks": [{"type": "command",
                    "command": "cat > \\\"$DUO_HOME/captured.json\\\"; echo 已捕获 >&2; exit 2"}]}
                ]}}
                """);
        Context root = boot();
        try {
            ToolResult result = execute(root, "probe_tool");
            assertTrue(result.isError() && result.value().toString().contains("已捕获"),
                    "钩子确已执行: " + result.value());
            Path captured = tempDir.resolve("home").resolve("captured.json");
            assertTrue(Files.exists(captured), "钩子应已把 stdin 载荷落盘");
            JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(Files.readString(captured));
            assertEquals("PreToolUse", payload.path("hook_event_name").asText());
            assertEquals("probe_tool", payload.path("tool_name").asText());
            assertTrue(payload.has("tool_input"), "tool_input 在场");
            assertTrue(payload.has("cwd"), "cwd 在场");
        } finally {
            root.dispose();
        }
    }

    @Test
    void matcherMultiSelectAndRegexRouteToDifferentTools() throws Exception {
        // 多选（|）与正则（^ 锚定）各命中各自工具
        writeHooksConfig("""
                {"hooks": {"PreToolUse": [
                  {"matcher": "plain_tool|never_exists", "hooks": [{"type": "command", "command": "echo plain 拦截 >&2; exit 2"}]},
                  {"matcher": "^probe", "hooks": [{"type": "command", "command": "echo probe 拦截 >&2; exit 2"}]}
                ]}}
                """);
        Context root = boot();
        try {
            ToolResult plain = execute(root, "plain_tool");
            assertTrue(plain.isError() && plain.value().toString().contains("plain 拦截"),
                    "多选命中 plain_tool: " + plain.value());
            ToolResult probe = execute(root, "probe_tool");
            assertTrue(probe.isError() && probe.value().toString().contains("probe 拦截"),
                    "正则命中 probe_tool: " + probe.value());
        } finally {
            root.dispose();
        }
    }

    private PluginState stateOf(Context root, String pluginName) {
        return root.snapshots().stream()
                .filter(s -> s.name().equals(pluginName))
                .findFirst()
                .orElseThrow()
                .state();
    }
}
