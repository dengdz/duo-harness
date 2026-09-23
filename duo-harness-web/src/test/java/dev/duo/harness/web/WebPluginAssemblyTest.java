package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolsService;
import dev.duo.harness.tools.fs.WorkspacePolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯 Web 装配用例（工单 M10-02）：最小 yml（tools + prompts + answers + web，
 * 无 CLI 装配层）启动后，ask_user 与计划呈交工具应在工具清单中——纯 Web 部署的
 * HITL 供给完整，不依赖终端装配在场。
 */
class WebPluginAssemblyTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebPluginAssemblyTest —— 纯 Web 装配：HITL 交互工具随装配注册（2 用例） ===");
    }

    interface ToolsView {

        ToolsService tools();
    }

    @Test
    void webAssemblyDoesNotSeizeLatestSession(@TempDir Path tempDir) throws Exception {
        // BUG-20260923-01 行为锚定：预置一个有内容的既有会话，Web 装配自建新会话、
        // 不抢占既有最新会话——它必须保持空闲，作为 CLI 下次启动的续接目标
        Path home = tempDir.resolve("duo-home");
        Path sessions = home.resolve("agent-sessions");
        Files.createDirectories(sessions);
        Path existing = sessions.resolve("20260101-000000-aaaa.jsonl");
        Files.writeString(existing, "{\"type\":\"user/message\",\"at\":1,\"text\":\"上次对话\"}\n");
        Files.writeString(home.resolve("config.yml"), """
                llm:
                  baseUrl: https://placeholder.local
                  apiKey: test-key
                  model: test-model
                """);
        System.setProperty(DuoHome.PROP_OVERRIDE, home.toString());
        try {
            Path yml = Path.of(WebPluginAssemblyTest.class.getResource("/web-assembly-test.yml").toURI());
            Context root = dev.duo.harness.core.api.boot.Boot.from(yml);
            try {
                assertFalse(dev.duo.harness.session.Session.isOccupied(existing),
                        "Web 装配不得抢占既有最新会话（应自建新会话）");
            } finally {
                root.dispose();
            }
        } finally {
            System.clearProperty(DuoHome.PROP_OVERRIDE);
        }
    }

    @Test
    void workspaceDeclaredAsOptionalDependency() {
        // 网络读档位恢复（PresenterAssembly.restorePermissionMode）与双面 /permission
        // 依赖本声明经 Web 插件 Context 惰性解析 workspace——缺失即内核"错误前移"拒绝
        // 读取（恢复被跳过、浏览器切档不可用）
        assertTrue(new WebPlugin().optionalInject().contains(WorkspacePolicy.SERVICE_NAME),
                "WebPlugin 须声明 workspace 可选依赖");
    }

    @Test
    void pureWebAssemblyRegistersInteractionTools(@TempDir Path tempDir) throws Exception {
        // duo home 重定向到临时目录并预置最小 config.yml（M14-01）：WebPlugin.apply 经
        // LlmConfig.load() 读 duo home——装配测试自此不依赖本机 ~/.duo 的真实状态
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
        } finally {
            System.clearProperty(DuoHome.PROP_OVERRIDE);
        }
    }
}
