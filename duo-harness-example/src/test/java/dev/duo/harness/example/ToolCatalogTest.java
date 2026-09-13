package dev.duo.harness.example;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.agent.ExitPlanModeTool;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.mcp.McpClientPlugin;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.AskUserTool;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具目录对账（"生成并校验"的最小形态）：复刻演示入口的全量装配（yml 插件 +
 * 编程挂载的 MCP 连接与 ask_user / exit_plan_mode），遍历工具注册表断言每个工具
 * 的名称与描述都出现在 docs/05-参考/工具目录.md——工具增删而文档未同步时在此炸出
 * （DSH tool-catalog 防漂移思路，工具量级上来后升级为生成器）。
 */
class ToolCatalogTest {

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface ToolsView {

        ToolsService tools();
    }

    /** answers 服务的视图接口（方法名即服务名）。 */
    interface AnswersView {

        InteractionService answers();
    }

    @TempDir
    Path tempDir;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ToolCatalogTest —— 工具目录对账：注册清单与文档防漂移（1 用例） ===");
    }

    @Test
    void catalogCoversEveryRegisteredTool() throws Exception {
        Path yml = Path.of(ToolCatalogTest.class.getResource("/agent-demo.yml").toURI());
        Context root = Boot.from(yml);
        try {
            // 复刻 AgentReplMain 的编程挂载段：MCP files 连接 + ask_user + exit_plan_mode
            Path sandbox = Files.createTempDirectory("duo-tool-catalog");
            Files.writeString(sandbox.resolve("notes.txt"), "工具目录对账用");
            var mcpConfig = JsonNodeFactory.instance.objectNode()
                    .put("serverName", "files")
                    .put("command", Path.of(System.getProperty("java.home"), "bin", "java").toString())
                    .put("requestTimeoutMs", 5_000);
            mcpConfig.putObject("reconnect")
                    .put("initialDelayMs", 100).put("maxDelayMs", 500).put("maxAttempts", 3);
            mcpConfig.putArray("args")
                    .add("-cp").add(DemoMain.subprocessClasspath())
                    .add("dev.duo.harness.example.mcpfs.MiniFileSystemServer")
                    .add(sandbox.toString());
            root.plugin(new McpClientPlugin(), mcpConfig).awaitStartup();

            ToolsService tools = root.as(ToolsView.class).tools();
            InteractionService answers = root.as(AnswersView.class).answers();
            Session session = Session.create(tempDir.resolve("sessions"));
            tools.register(root, new AskUserTool(answers));
            tools.register(root, new ExitPlanModeTool(answers, () -> session, () -> { }));

            String doc = Files.readString(catalogPath(), StandardCharsets.UTF_8);
            int checked = 0;
            for (ToolDefinition tool : tools.list()) {
                assertTrue(doc.contains(tool.name()),
                        "工具目录缺少工具名: " + tool.name() + "——增删工具请同步 docs/05-参考/工具目录.md");
                assertTrue(doc.contains(tool.description()),
                        "工具目录缺少工具描述: " + tool.name() + "（描述以 ToolsService 注册为准）");
                checked++;
            }
            System.out.println("[对账] 工具目录覆盖 " + checked + " 个注册工具");
            assertTrue(checked >= 5, "demo 全量装配应至少注册 5 个工具（含 MCP 远端），实际 " + checked);
        } finally {
            root.dispose();
        }
    }

    /** 文档路径：surefire 工作目录是模块目录，docs/ 在仓库根。 */
    private static Path catalogPath() {
        Path path = Path.of("../docs/05-参考/工具目录.md");
        return Files.exists(path) ? path : Path.of("docs/05-参考/工具目录.md");
    }
}
