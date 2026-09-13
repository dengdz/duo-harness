package dev.duo.harness.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AGENTS.md 注入用例：两文件按序拼接（用户全局 → 项目根）、单文件、全缺 null、
 * 64KB 预算截断带注明、.git 定根。
 */
class AgentsMdTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：AgentsMdTest —— AGENTS.md 注入：按序拼接、单文件、全缺 null、"
                + "预算截断、.git 定根（5 用例） ===");
    }

    @TempDir
    Path tempDir;

    /** 建一个"项目"：project/AGENTS.md + project/.git 标记，返回 cwd（project 内层子目录）。 */
    private Path projectWith(String agentsContent) throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve(".git"));
        if (agentsContent != null) {
            Files.writeString(project.resolve("AGENTS.md"), agentsContent);
        }
        Path cwd = project.resolve("src").resolve("main");
        Files.createDirectories(cwd);
        return cwd;
    }

    @Test
    void bothFilesJoinedInOrder() throws IOException {
        Path user = tempDir.resolve("user-agents.md");
        Files.writeString(user, "个人偏好：简洁。");
        Path cwd = projectWith("项目约定：先测试后提交。");

        String text = AgentsMd.load(cwd, user, 64 * 1024);

        assertEquals("个人偏好：简洁。\n\n项目约定：先测试后提交。", text, "用户全局在前、项目根在后");
    }

    @Test
    void projectOnlyWhenNoUserFile() throws IOException {
        Path cwd = projectWith("项目约定内容。");

        String text = AgentsMd.load(cwd, tempDir.resolve("不存在.md"), 64 * 1024);

        assertEquals("项目约定内容。", text);
    }

    @Test
    void returnsNullWhenNoFilesAtAll() throws IOException {
        Path cwd = projectWith(null);

        assertNull(AgentsMd.load(cwd, tempDir.resolve("不存在.md"), 64 * 1024),
                "两个候选都不存在 → null（不注册空片段）");
    }

    @Test
    void budgetOverflowTruncatesWithNote() throws IOException {
        Path user = tempDir.resolve("user-agents.md");
        Files.writeString(user, "A".repeat(100) + "\n\n尾部内容");
        Path cwd = projectWith("项目约定。");

        String text = AgentsMd.load(cwd, user, 64);

        assertEquals("A".repeat(64) + "\n\n（AGENTS.md 内容超预算，已截断）", text,
                "截断保留头部 64 字符并附加注明");
        assertTrue(text.contains("项目约定。") == false || text.indexOf("项目约定。") >= 64,
                "预算内只保留头部（项目根部分被截断）");
    }

    @Test
    void projectRootLocatedByGitMarker() throws IOException {
        // cwd 深于项目根两层，AGENTS.md 放项目根——.git 向上定位应命中
        Path cwd = projectWith("根约定。");
        Files.writeString(cwd.resolve("AGENTS.md"), "子目录约定。");

        String text = AgentsMd.load(cwd, tempDir.resolve("无"), 64 * 1024);

        assertEquals("根约定。", text, "取 .git 所在的项目根 AGENTS.md，而非 cwd 的");
    }
}
