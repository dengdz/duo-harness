package dev.duo.harness.web;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.agent.commands.CommandsPlugin;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.tools.InteractionPlugin;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import dev.duo.harness.agent.prompt.PromptPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 行序契约 fail-fast 用例（M27 工单 05，扫描册 H-05）：交互服务已有回答者在册时，
 * Web 装配即点名失败——回答者注册序 = 亲和路由兜底序（ADR-0020 决策 7），web 后
 * 注册即失去「审批/提问 Web 卡片优先」语义（BUG-20260923-01 实证的静默串语义，
 * 就此显式化）。既有装配（无先行回答者）不受影响，由 WebPluginAssemblyTest 回归。
 */
class WebPluginRowOrderContractTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebPluginRowOrderContractTest —— 行序契约 fail-fast："
                + "回答者在册时 web 装配点名失败（1 用例） ===");
    }

    interface ToolsView {

        ToolsService tools();
    }

    interface AnswersView {

        InteractionService answers();
    }

    @Test
    void webPluginFailsFastWhenAnswererAlreadyRegistered(@TempDir Path tempDir) throws Exception {
        // duo home 重定向（WebPlugin.apply 经 LlmConfig.load() 读 duo home——
        // 与 WebPluginAssemblyTest 同款隔离，本用例在校验点即失败、不触 LLM）
        Path home = tempDir.resolve("duo-home");
        Files.createDirectories(home);
        Files.writeString(home.resolve("config.yml"), """
                llm:
                  baseUrl: https://placeholder.local
                  apiKey: test-key
                  model: test-model
                """);
        System.setProperty(DuoHome.PROP_OVERRIDE, home.toString());
        try {
            Context root = Context.root();
            try {
                root.plugin(new ToolsPlugin(), null).awaitStartup();
                root.plugin(new PromptPlugin(), JsonNodeFactory.instance.objectNode()).awaitStartup();
                root.plugin(new InteractionPlugin(), null).awaitStartup();
                root.plugin(new CommandsPlugin(), JsonNodeFactory.instance.objectNode()).awaitStartup();

                // 模拟「cli 行在前」：web 装配前已有回答者在册（匿名实现——web 在
                // apply 校验点即失败，本回答者永不被询问）
                root.as(AnswersView.class).answers().register(root, request -> null);

                PluginException thrown = assertThrows(PluginException.class,
                        () -> root.plugin(new WebPlugin(), JsonNodeFactory.instance.objectNode()).awaitStartup());
                // 内核包装插件异常（cause 保留原始）——沿 cause 链找「行序契约」点名
                StringBuilder messages = new StringBuilder(thrown.getMessage());
                for (Throwable cause = thrown.getCause(); cause != null; cause = cause.getCause()) {
                    messages.append(" / ").append(cause.getMessage());
                }
                assertTrue(messages.toString().contains("行序契约"),
                        "失败信息应点名行序契约与调整指引: " + messages);
            } finally {
                root.dispose();
            }
        } finally {
            System.clearProperty(DuoHome.PROP_OVERRIDE);
        }
    }
}
