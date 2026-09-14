package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯 Web 装配用例（工单 M10-02）：最小 yml（tools + prompts + answers + web，
 * 无 CLI 装配层）启动后，ask_user 与计划呈交工具应在工具清单中——纯 Web 部署的
 * HITL 供给完整，不依赖终端装配在场。
 */
class WebPluginAssemblyTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebPluginAssemblyTest —— 纯 Web 装配：HITL 交互工具随装配注册（1 用例） ===");
    }

    interface ToolsView {

        ToolsService tools();
    }

    @Test
    void pureWebAssemblyRegistersInteractionTools() throws Exception {
        // 注意：WebPlugin.apply 经 LlmConfig.load() 读取 ~/.duo/config.yml——
        // 与 AgentReplMainTest 的 demo yml 启动用例同一先例（依赖本机真实配置）
        Path yml = Path.of(WebPluginAssemblyTest.class.getResource("/web-assembly-test.yml").toURI());
        Context root = dev.duo.harness.core.api.boot.Boot.from(yml);
        try {
            ToolsService tools = root.as(ToolsView.class).tools();
            List<String> names = tools.list().stream().map(ToolDefinition::name).toList();
            assertTrue(names.contains("ask_user"), "纯 Web 装配应含 ask_user 提问工具: " + names);
            assertTrue(names.contains("exit_plan_mode"), "纯 Web 装配应含计划呈交工具: " + names);
        } finally {
            root.dispose();
        }
    }
}
