package dev.duo.harness.agent.deliverable;

import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.session.SessionExport.ChangeReport;
import dev.duo.harness.session.SessionExport.ChangeRow;
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
 * 变更摘要供给用例（M26 工单 05，ADR-0028 决策七）：git 仓库内首尾快照对账
 * （tracked 改动行数 + 新增未跟踪文件）、非 git 目录与 resume 会话退化、工具
 * 记录聚合。git 迷你仓库夹具（@TempDir + git init，全仓首例——03 审查记档先例）。
 */
class ChangeSummaryTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ChangeSummaryTest —— 变更摘要供给：git 首尾快照对账"
                + "（tracked 行数/新增未跟踪）、非 git 与 resume 退化、工具记录聚合（6 用例） ===");
    }

    @TempDir
    Path tempDir;

    /** git 迷你仓库：init + 首提交（stash create/rev-parse 需要用户身份——本仓库配置注入）。 */
    private Path gitRepo(String name) throws Exception {
        Path repo = tempDir.resolve(name);
        Files.createDirectories(repo);
        git(repo, "init");
        git(repo, "config", "user.email", "test@duo.local");
        git(repo, "config", "user.name", "duo-test");
        Files.writeString(repo.resolve("seed.txt"), "seed\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-m", "seed");
        return repo;
    }

    private static void git(Path dir, String... args) throws Exception {
        var pb = new ProcessBuilder("git", "-C", dir.toString());
        pb.command().addAll(List.of(args));
        pb.start().waitFor();
    }

    private Session sessionIn(Path cwd) {
        return Session.create(tempDir.resolve("sessions").resolve(String.valueOf(cwd.hashCode())),
                cwd);
    }

    @Test
    void gitDiffRowsWithLineCountsAndNewFiles() throws Exception {
        Path repo = gitRepo("repo-a");
        Session session = sessionIn(repo);
        ChangeSummary.markStart(session); // 空会话 → 首拍

        // 会话期间：改 tracked + 新建未跟踪
        Files.writeString(repo.resolve("seed.txt"), "seed\nmore\nlines\n");
        Files.writeString(repo.resolve("new-file.txt"), "brand\nnew\n");

        ChangeReport report = ChangeSummary.report(session);
        assertTrue(report.gitAvailable(), "git 仓库内对账可用");
        assertEquals(2, report.gitRows().size(), "tracked 改动 + 新增未跟踪各一行: "
                + report.gitRows());
        ChangeRow seed = report.gitRows().stream()
                .filter(r -> r.path().equals("seed.txt")).findFirst().orElseThrow();
        assertEquals("2", seed.added(), "行数对账（more/lines 两行新增，seed 行未动为上下文）");
        assertEquals("0", seed.deleted(), "无删除——原行保留为上下文（git 语义）");
        ChangeRow added = report.gitRows().stream()
                .filter(r -> r.path().equals("new-file.txt")).findFirst().orElseThrow();
        assertEquals("2", added.added(), "新增文件行数（Java 计）");
        assertEquals("0", added.deleted());
        session.close();
    }

    @Test
    void cleanSessionReportsNoChanges() throws Exception {
        Path repo = gitRepo("repo-b");
        Session session = sessionIn(repo);
        ChangeSummary.markStart(session);
        // 会话期间无任何文件改动

        ChangeReport report = ChangeSummary.report(session);
        assertTrue(report.gitAvailable());
        assertTrue(report.gitRows().isEmpty(), "无文件变更");
        session.close();
    }

    @Test
    void nonGitDirectoryDegradesToToolPaths() throws Exception {
        Path plain = tempDir.resolve("plain");
        Files.createDirectories(plain);
        Session session = Session.create(tempDir.resolve("sessions").resolve("plain"), plain);
        ChangeSummary.markStart(session);

        ChangeReport report = ChangeSummary.report(session);
        assertFalse(report.gitAvailable(), "非 git 目录：对账不可用");
        assertTrue(report.gitRows().isEmpty());
        session.close();
    }

    @Test
    void resumedSessionSkipsSnapshotAndDegrades() throws Exception {
        Path repo = gitRepo("repo-c");
        Session resumed = Session.create(tempDir.resolve("sessions").resolve("repo-c"), repo);
        resumed.append(SessionEvent.userMessage("历史消息")); // 非空会话 = resume 形态

        ChangeSummary.markStart(resumed); // 有事件 → 不拍
        ChangeReport report = ChangeSummary.report(resumed);
        assertFalse(report.gitAvailable(), "resume 会话不拍快照 → 退化");
        assertTrue(report.gitRows().isEmpty());
        resumed.close();
    }

    @Test
    void toolPathsAggregateWriteAndEditCalls() throws Exception {
        Path plain = tempDir.resolve("plain2");
        Files.createDirectories(plain);
        Session session = Session.create(tempDir.resolve("sessions").resolve("plain2"), plain);
        session.append(SessionEvent.toolCall("c1", "write", "{\"path\":\"src/A.java\"}"));
        session.append(SessionEvent.toolCall("c2", "edit", "{\"path\":\"src/A.java\"}"));
        session.append(SessionEvent.toolCall("c3", "write", "{\"path\":\"src/B.java\"}"));
        session.append(SessionEvent.toolCall("c4", "read", "{\"path\":\"src/C.java\"}"));

        ChangeReport report = ChangeSummary.report(session);
        // read 不入口径；write+edit 同文件去重
        assertEquals(List.of("src/A.java", "src/B.java"), report.toolPaths(),
                "write/edit 聚合去重保序，read 排除");
        session.close();
    }

    @Test
    void markStartSkipsNonEmptySoReportStaysStable() throws Exception {
        Path repo = gitRepo("repo-d");
        Session session = sessionIn(repo);
        ChangeSummary.markStart(session);
        Files.writeString(repo.resolve("seed.txt"), "changed\n");
        ChangeReport first = ChangeSummary.report(session);
        // 重复导出（不重拍首帧）：结果一致
        ChangeReport second = ChangeSummary.report(session);
        assertEquals(first.gitRows(), second.gitRows(), "重复导出对账幂等");
        session.close();
    }
}
