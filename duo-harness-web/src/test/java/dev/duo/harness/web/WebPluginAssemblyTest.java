package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void connectorStatusDeclaredAsOptionalDependency() {
        // BUG-20261002-01 回归锁：MCP 行在场时（connectorStatus 服务已发布），状态面
        // 连接器块经 Web 插件 Context 读板——未声明 optionalInject 则内核「错误前移」
        // 拒读（hasService 真 ≠ 可读，2026-09-22 记档同族），statusJson 抛异常且 route
        // 无兜底 → 连接裸关（实测空响应形态）
        assertTrue(new WebPlugin().optionalInject().contains(dev.duo.harness.tools.ConnectorStatusBoard.SERVICE_NAME),
                "WebPlugin 须声明 connectorStatus 可选依赖（MCP 行在场时状态面连接器块可读）");
    }

    @Test
    void skillRegistryDeclaredAsOptionalDependency() {
        // BUG-20261002-06 回归锁：斜杠解释链第二级「技能直调」经 skillsOrNull 读注册表
        // ——未声明 optionalInject 则被声明闸门拒读并被 catch 吞成 null，直调级永远
        // 未命中（/技能名 全部「未知命令」且提示不带技能清单，「hasService 真 ≠ 可读」
        // 家族第四次重现）
        assertTrue(new WebPlugin().optionalInject().contains(dev.duo.harness.agent.skills.SkillRegistry.SERVICE_NAME),
                "WebPlugin 须声明 skills 可选依赖（斜杠技能直调第二级）");
    }

    @Test
    void statusServesConnectorSnapshotThroughPluginDeclarationGate(@TempDir Path tempDir) throws Exception {
        // BUG-20261002-01 端到端回归锁：MCP 首行发布形态（provideBoardService 同款，
        // 板发布在注册表）+ 全插件树启动 → GET /api/status 必须 200 且含连接器快照。
        // 修复前：WebPlugin 未声明 connectorStatus → 插件 Context 内 as() 被声明闸门拒
        // → statusJson 抛 IllegalStateException → route 无兜底 → 连接裸关（HTTP 无响应）
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
            JsonNode emptyCfg = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            root.plugin(new dev.duo.harness.tools.ToolsPlugin(), null).awaitStartup();
            root.plugin(new dev.duo.harness.agent.prompt.PromptPlugin(), emptyCfg).awaitStartup();
            root.plugin(new dev.duo.harness.tools.InteractionPlugin(), null).awaitStartup();
            root.plugin(new dev.duo.harness.agent.commands.CommandsPlugin(), emptyCfg).awaitStartup();
            // MCP 首行发布形态（McpClientSupport.provideBoardService 同款）：板为共享单例
            dev.duo.harness.tools.ConnectorStatusBoard board =
                    dev.duo.harness.tools.ConnectorStatusBoard.shared();
            board.update("echo", "CONNECTED", "已连接");
            root.provide(dev.duo.harness.tools.ConnectorStatusBoard.SERVICE_NAME, board);
            WebPlugin web = new WebPlugin();
            JsonNode webCfg = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                    .objectNode().put("port", 0);
            root.plugin(web, webCfg).awaitStartup();
            int port = web.face().port();
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + port + "/api/status"))
                    .header("X-Duo-Token", web.face().authToken())
                    .GET().build();
            java.net.http.HttpResponse<String> res =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, res.statusCode(), "状态端点 200（修复前：声明闸门拒读 → 连接裸关）");
            JsonNode json = new ObjectMapper().readTree(res.body());
            assertTrue(json.path("connector").isArray() && json.path("connector").size() > 0,
                    "连接器快照在列: " + res.body());
            assertEquals("echo", json.path("connector").get(0).path("server").asText(), "server 名在");
            assertEquals("CONNECTED", json.path("connector").get(0).path("state").asText(), "状态在");
        } finally {
            System.clearProperty(DuoHome.PROP_OVERRIDE);
        }
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
