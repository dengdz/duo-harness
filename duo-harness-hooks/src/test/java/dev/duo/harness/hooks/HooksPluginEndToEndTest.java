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
                + "fail-open、空转、坏配置 FAILED 点名、事件跳过（5 用例） ===");
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

        @Override
        public Disposable apply(Context ctx, Void config) {
            ToolsService tools = ctx.as(ToolsView.class).tools();
            tools.register(ctx, simpleTool("probe_tool", "probe-ok"));
            tools.register(ctx, simpleTool("plain_tool", "plain-ok"));
            return null;
        }

        private ToolDefinition simpleTool(String name, String reply) {
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

    private PluginState stateOf(Context root, String pluginName) {
        return root.snapshots().stream()
                .filter(s -> s.name().equals(pluginName))
                .findFirst()
                .orElseThrow()
                .state();
    }
}
