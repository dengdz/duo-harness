package dev.duo.harness.agent.prompt;

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
                + "预算截断、.git 定根、嵌套链浅到深、fs 增量、meta_user 段（9 用例） ===");
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
        // M25 工单 07 嵌套链语义：项目根在前、cwd 的 AGENTS.md 入链在后（浅到深）
        Path cwd = projectWith("根约定。");
        Files.writeString(cwd.resolve("AGENTS.md"), "子目录约定。");

        String text = AgentsMd.load(cwd, tempDir.resolve("无"), 64 * 1024);

        assertEquals("根约定。\n\n子目录约定。", text,
                "项目根（.git 定位）在前、cwd 的子目录约定入链在后");
    }

    @Test
    void 嵌套链多层浅到深拼接() throws IOException {
        // root → src → src/main 三层约定，浅到深拼接（由泛到专）
        Path cwd = projectWith("根约定。");
        Files.writeString(cwd.getParent().resolve("AGENTS.md"), "src 层约定。");
        Files.writeString(cwd.resolve("AGENTS.md"), "main 层约定。");

        String text = AgentsMd.load(cwd, tempDir.resolve("无"), 64 * 1024);

        assertEquals("根约定。\n\nsrc 层约定。\n\nmain 层约定。", text,
                "嵌套链浅到深：根 → src → main");
    }

    @Test
    void 链上中间层缺失自然跳过() throws IOException {
        // src 层无 AGENTS.md——根与 main 层照常拼接，缺失层零占位
        Path cwd = projectWith("根约定。");
        Files.writeString(cwd.resolve("AGENTS.md"), "main 层约定。");

        String text = AgentsMd.load(cwd, tempDir.resolve("无"), 64 * 1024);

        assertEquals("根约定。\n\nmain 层约定。", text, "中间缺失层跳过不占位");
    }

    @Test
    void fs增量发现新文件下一轮即见() throws IOException {
        // M7#3「fs 操作后增量发现」：会话中新建目录/文件，下一次 load（即下一轮请求）入链
        Path cwd = projectWith("根约定。");
        String before = AgentsMd.load(cwd, tempDir.resolve("无"), 64 * 1024);
        assertEquals("根约定。", before, "初轮仅根");

        Files.writeString(cwd.resolve("AGENTS.md"), "会话中新建的约定。");
        String after = AgentsMd.load(cwd, tempDir.resolve("无"), 64 * 1024);

        assertEquals("根约定。\n\n会话中新建的约定。", after, "现发现现读——新文件下一轮即入链");
    }

    @Test
    void metaUser段形态与降级() throws IOException {
        // 无链文件 → null 零注入；有内容 → <agents-md> 标签 + 免责语包裹
        Path cwd = projectWith(null);
        assertNull(AgentsMd.metaUserSection(cwd, tempDir.resolve("无"), 64 * 1024),
                "链上无文件 → 无 meta_user 段");

        Files.writeString(cwd.getParent().getParent().resolve("AGENTS.md"), "根约定。");
        String section = AgentsMd.metaUserSection(cwd, tempDir.resolve("无"), 64 * 1024);
        assertTrue(section.startsWith("<agents-md>"), "段以 agents-md 标签包裹: " + section);
        assertTrue(section.contains("根约定。"), "段内含链内容");
        assertTrue(section.contains("以用户为准"), "段内含免责语: " + section);
        assertTrue(section.endsWith("</agents-md>"), "段以闭合标签收尾");
    }
}
