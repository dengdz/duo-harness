package dev.duo.harness.agent.prompt;

import dev.duo.harness.core.api.Context;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AGENTS.md 链插件装配用例（M25 工单 07）：服务发布与视图可达、meta_user 段现读、
 * budgetChars 配置、链缺席零注入。user.dir 覆盖为本套件约定（测后恢复）。
 */
class AgentsMdPluginTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：AgentsMdPluginTest —— AGENTS.md 链插件：服务发布、"
                + "meta_user 段现读、预算配置（3 用例） ===");
    }

    interface AgentsMdView {

        AgentsMdChain agentsMd();
    }

    @TempDir
    Path tempDir;

    private Path writeYml(String config) throws IOException {
        Path yml = tempDir.resolve("boot-agentsmd-test.yml");
        Files.writeString(yml, """
                plugins:
                  - id: agents-md
                    name: dev.duo.harness.agent.prompt.AgentsMdPlugin
                    config: %s
                """.formatted(config));
        return yml;
    }

    @Test
    void 服务发布且段现读链内容() throws IOException {
        // cwd 覆盖到临时项目：root/AGENTS.md + root/sub/AGENTS.md 嵌套链
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve(".git"));
        Files.createDirectories(project.resolve("sub"));
        Files.writeString(project.resolve("AGENTS.md"), "根约定。");
        Files.writeString(project.resolve("sub").resolve("AGENTS.md"), "子层约定。");
        Path yml = writeYml("{}");
        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("duo.home");
        System.setProperty("user.dir", project.resolve("sub").toString());
        System.setProperty("duo.home", tempDir.resolve("isolated-home").toString());
        Context root = null;
        try {
            root = dev.duo.harness.core.api.boot.Boot.from(yml);
            AgentsMdChain chain = root.as(AgentsMdView.class).agentsMd();
            assertNotNull(chain, "agentsMd 服务随 agents-md 行发布");
            String section = chain.section();
            assertTrue(section.contains("根约定。") && section.contains("子层约定。"),
                    "meta_user 段含嵌套链内容: " + section);
            assertTrue(section.indexOf("根约定。") < section.indexOf("子层约定。"),
                    "浅层在前深层在后");

            // fs 增量：新写一层约定，下一次 section() 即见（现发现现读）
            Files.writeString(project.resolve("sub").resolve("AGENTS.md"),
                    "子层约定。\n\n追加约定。");
            assertTrue(chain.section().contains("追加约定。"), "现读——新内容下轮即见");
        } finally {
            System.setProperty("user.dir", originalDir);
            if (originalHome != null) {
                System.setProperty("duo.home", originalHome);
            } else {
                System.clearProperty("duo.home");
            }
            if (root != null) {
                root.dispose();
            }
        }
    }

    @Test
    void 链上无文件零注入() throws IOException {
        Path empty = tempDir.resolve("empty-project");
        Files.createDirectories(empty);
        Path yml = writeYml("{}");
        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("duo.home");
        System.setProperty("user.dir", empty.toString());
        // duo.home 重定向（FsBashToolTest 先例）：用户全局取链的判定不依赖本机
        // ~/.duo/AGENTS.md 是否存在——装配测试与本机环境解耦
        System.setProperty("duo.home", tempDir.resolve("isolated-home").toString());
        Context root = null;
        try {
            root = dev.duo.harness.core.api.boot.Boot.from(yml);
            assertNull(root.as(AgentsMdView.class).agentsMd().section(),
                    "链上无文件 → 无 meta_user 段（零注入）");
        } finally {
            System.setProperty("user.dir", originalDir);
            if (originalHome != null) {
                System.setProperty("duo.home", originalHome);
            } else {
                System.clearProperty("duo.home");
            }
            if (root != null) {
                root.dispose();
            }
        }
    }

    @Test
    void budgetChars配置透传() throws IOException {
        Path project = tempDir.resolve("budget-project");
        Files.createDirectories(project.resolve(".git"));
        Files.writeString(project.resolve("AGENTS.md"), "x".repeat(100));
        Path yml = writeYml("{budgetChars: 20}");
        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("duo.home");
        System.setProperty("user.dir", project.toString());
        System.setProperty("duo.home", tempDir.resolve("isolated-home").toString());
        Context root = null;
        try {
            root = dev.duo.harness.core.api.boot.Boot.from(yml);
            String section = root.as(AgentsMdView.class).agentsMd().section();
            assertTrue(section.contains("已截断"), "超预算截断标注: " + section);
            assertEquals(20, root.as(AgentsMdView.class).agentsMd().budgetChars(),
                    "配置透传");
        } finally {
            System.setProperty("user.dir", originalDir);
            if (originalHome != null) {
                System.setProperty("duo.home", originalHome);
            } else {
                System.clearProperty("duo.home");
            }
            if (root != null) {
                root.dispose();
            }
        }
    }
}
