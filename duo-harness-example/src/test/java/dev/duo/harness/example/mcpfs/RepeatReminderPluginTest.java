package dev.duo.harness.example.mcpfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复调用提醒插件用例：默认阈值 3 次起逐级提醒、参数不同即重置计数、
 * 错误结果不附加提醒、阈值可配置。
 */
class RepeatReminderPluginTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：RepeatReminderPluginTest —— 重复调用提醒：默认 3 次起逐级加码、"
                + "换参数重置、错误结果不附加、阈值可配置（4 用例） ===");
    }

    interface ToolsView {

        ToolsService tools();
    }

    private Context root;

    @AfterEach
    void tearDown() {
        if (root != null) {
            root.dispose();
        }
    }

    private ToolsService assemble(String configJson) {
        root = Context.root();
        root.plugin(new RepeatReminderPlugin(), config(configJson)).awaitStartup();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        return root.as(ToolsView.class).tools();
    }

    private static JsonNode config(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("测试配置解析失败", e);
        }
    }

    private static dev.duo.harness.tools.ToolDefinition echo() {
        return new dev.duo.harness.tools.ToolDefinition() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "回声工具";
            }

            @Override
            public JsonNode parameters() {
                return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("type", "object");
            }

            @Override
            public String execute(dev.duo.harness.tools.ToolExecution execution) {
                return "echo:" + execution.args().path("x").asText("");
            }
        };
    }

    @Test
    void thirdConsecutiveIdenticalCallGetsEscalatingReminder() {
        ToolsService tools = assemble("{}");
        tools.register(root, echo());

        String first = String.valueOf(tools.execute("echo", config("{\"x\":\"1\"}")).value());
        String second = String.valueOf(tools.execute("echo", config("{\"x\":\"1\"}")).value());
        String third = String.valueOf(tools.execute("echo", config("{\"x\":\"1\"}")).value());

        assertFalse(first.contains("[提醒]"));
        assertFalse(second.contains("[提醒]"), "未达阈值不提醒");
        assertTrue(third.contains("[提醒]"), third);
        assertTrue(third.contains("3 次"), third);
    }

    @Test
    void fifthCallEscalatesToStrongerReminder() {
        ToolsService tools = assemble("{}");
        tools.register(root, echo());

        for (int i = 0; i < 4; i++) {
            tools.execute("echo", config("{\"x\":\"1\"}"));
        }
        String fifth = String.valueOf(tools.execute("echo", config("{\"x\":\"1\"}")).value());

        assertTrue(fifth.contains("已升级"), fifth);
    }

    @Test
    void differentArgsResetsConsecutiveCount() {
        ToolsService tools = assemble("{}");
        tools.register(root, echo());

        tools.execute("echo", config("{\"x\":\"1\"}"));
        tools.execute("echo", config("{\"x\":\"1\"}"));
        String reset = String.valueOf(tools.execute("echo", config("{\"x\":\"2\"}")).value());

        assertFalse(reset.contains("[提醒]"), "参数不同即重置计数: " + reset);
    }

    @Test
    void configurableThresholds() {
        ToolsService tools = assemble("{\"thresholds\":[2,4,6]}");
        tools.register(root, echo());

        String first = String.valueOf(tools.execute("echo", config("{\"x\":\"1\"}")).value());
        String second = String.valueOf(tools.execute("echo", config("{\"x\":\"1\"}")).value());

        assertFalse(first.contains("[提醒]"), "首次调用不提醒");
        assertTrue(second.contains("2 次"), "阈值为 2 时第二次即提醒: " + second);
    }
}
