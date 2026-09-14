package dev.duo.harness.cli;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯 CLI 装配用例（M11-02，对齐 WebPluginAssemblyTest 先例）：最小 yml（tools +
 * prompts + answers + skills + cli）启动后，ask_user 与计划呈交工具应在册——
 * 纯 CLI 部署的 HITL 供给完整。
 *
 * <p>注意：CliPlugin 经 LlmConfig 装配读取 ~/.duo/config.yml（WebPluginAssemblyTest
 * 同款环境依赖，先例披露）；System.in 在 surefire 下通常立即可读 EOF——REPL 线程
 * 随即转 idle（守护虚拟线程，不阻塞测试）。</p>
 */
class CliPluginAssemblyTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：CliPluginAssemblyTest —— 纯 CLI 装配：HITL 交互工具随装配注册（1 用例） ===");
    }

    interface ToolsView {

        ToolsService tools();
    }

    @Test
    void pureCliAssemblyRegistersInteractionTools() throws Exception {
        // 空 stdin：默认构造的 CliPlugin 读 System.in——surefire 的 stdin 不保证 EOF，
        // 置空流让 REPL 立即转 idle（守护虚拟线程不阻塞测试；会话锁随 idle 释放）
        java.io.InputStream originalIn = System.in;
        System.setIn(new java.io.ByteArrayInputStream(new byte[0]));
        Path yml = Path.of(CliPluginAssemblyTest.class.getResource("/cli-assembly-test.yml").toURI());
        Context root = dev.duo.harness.core.api.boot.Boot.from(yml);
        try {
            ToolsService tools = root.as(ToolsView.class).tools();
            List<String> names = tools.list().stream().map(ToolDefinition::name).toList();
            assertTrue(names.contains("ask_user"), "纯 CLI 装配应含 ask_user 提问工具: " + names);
            assertTrue(names.contains("exit_plan_mode"), "纯 CLI 装配应含计划呈交工具: " + names);
        } finally {
            root.dispose();
            System.setIn(originalIn);
        }
    }
}
