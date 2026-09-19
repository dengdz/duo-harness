package dev.duo.harness.example;

import dev.duo.harness.agent.plan.ExitPlanModeTool;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.example.support.DemoYml;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具目录对账（"生成并校验"的最小形态）：Boot 装载演示全量装配（agent-demo.yml），
 * 补齐呈现位的交互工具查重注册，遍历工具注册表断言每个工具的名称与描述都出现在
 * docs/05-参考/工具目录.md——工具增删而文档未同步时在此炸出（DSH tool-catalog
 * 防漂移思路，工具量级上来后升级为生成器）。
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
        // cli 行的 REPL 读 System.in——置空流让 REPL 立即 idle，否则单跑本类时 JVM 退出
        // 被非守护 REPL 线程挂起（同 fork 先跑过 AgentReplMainTest 时被其置空掩盖）
        System.setIn(new java.io.ByteArrayInputStream(new byte[0]));
        // 随机端口副本：本机在跑 demo 实例时不抢 18080（BUG-20260915-02）
        Path yml = DemoYml.ephemeralPortCopy(tempDir, "/agent-demo.yml");
        Context root = Boot.from(yml);
        try {
            ToolsService tools = root.as(ToolsView.class).tools();
            InteractionService answers = root.as(AnswersView.class).answers();
            Session session = Session.create(tempDir.resolve("sessions"));
            // 复刻呈现位的查重注册：agent-demo.yml 的呈现位插件已注册交互工具时让位
            if (tools.list().stream().noneMatch(d -> "ask_user".equals(d.name()))) {
                tools.register(root, new AskUserTool(answers));
            }
            if (tools.list().stream().noneMatch(d -> "exit_plan_mode".equals(d.name()))) {
                tools.register(root, new ExitPlanModeTool(answers, "cli", () -> session, () -> { }));
            }

            List<String> names = tools.list().stream().map(ToolDefinition::name).toList();
            // agent demo 装配仅本机工具族——MCP 远端工具出现在注册表即为装配漂移
            assertTrue(names.stream().noneMatch(n -> n.startsWith("mcp__")),
                    "agent demo 不应注册 MCP 远端工具: " + names);
            for (String fsTool : List.of("read", "write", "edit", "glob", "grep", "bash")) {
                assertTrue(names.contains(fsTool), "fs 六件缺 " + fsTool + ": " + names);
            }

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
            assertTrue(checked >= 8, "demo 全量装配应至少注册 8 个工具（fs 六件 + 交互工具等），实际 " + checked);
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