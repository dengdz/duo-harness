package dev.duo.harness.tools.fs;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * workspace 策略判定矩阵用例（M12-01，ADR-0012）：三档 × {workspace 内写,
 * 越界写, bash, 读类} 全组合 + 路径包含性安全（symlink 越界、`..` 穿越、相对
 * 路径解析）。
 */
class WorkspacePolicyTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private Path outside;

    private WorkspacePolicy policy(WorkspacePolicy.Mode mode) throws IOException {
        workspace = tempDir.resolve("ws");
        outside = tempDir.resolve("outside");
        Files.createDirectories(workspace);
        Files.createDirectories(outside);
        return new WorkspacePolicy(workspace, mode);
    }

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WorkspacePolicyTest —— workspace 策略判定矩阵："
                + "三档 × 路径包含性（9 用例） ===");
    }

    @Test
    void readOnlyAllowsReadsAndAsksWrites() throws IOException {
        WorkspacePolicy policy = policy(WorkspacePolicy.Mode.READ_ONLY);
        for (String tool : List.of("read", "glob", "grep")) {
            assertEquals(WorkspacePolicy.Decision.ALLOW, policy.decide(tool, null),
                    tool + " 在 read-only 档放行");
        }
        Path inWs = Files.writeString(workspace.resolve("a.txt"), "内容");
        assertEquals(WorkspacePolicy.Decision.ASK, policy.decide("write", inWs),
                "read-only 档写操作一律 ask");
        assertEquals(WorkspacePolicy.Decision.ASK, policy.decide("bash", null),
                "read-only 档 bash 一律 ask");
    }

    @Test
    void workspaceWriteAllowsInWorkspaceWritesOnly() throws IOException {
        WorkspacePolicy policy = policy(WorkspacePolicy.Mode.WORKSPACE_WRITE);
        Path inWs = Files.writeString(workspace.resolve("a.txt"), "内容");
        Path outWs = Files.writeString(outside.resolve("b.txt"), "越界");

        assertEquals(WorkspacePolicy.Decision.ALLOW, policy.decide("write", inWs),
                "workspace 内写放行");
        assertEquals(WorkspacePolicy.Decision.ASK, policy.decide("write", outWs),
                "越界写 ask");
        assertEquals(WorkspacePolicy.Decision.ASK, policy.decide("bash", null),
                "workspace-write 档 bash 一律 ask");
    }

    @Test
    void dangerFullAccessAllowsEverything() throws IOException {
        WorkspacePolicy policy = policy(WorkspacePolicy.Mode.DANGER_FULL_ACCESS);
        Path outWs = Files.writeString(outside.resolve("b.txt"), "越界");
        assertEquals(WorkspacePolicy.Decision.ALLOW, policy.decide("write", outWs));
        assertEquals(WorkspacePolicy.Decision.ALLOW, policy.decide("bash", null));
    }

    @Test
    void symlinkEscapeDetectedAsOutside() throws IOException {
        // workspace 内符号链接指向外部 → realpath 解析后不在 workspace → 拒绝
        WorkspacePolicy policy = policy(WorkspacePolicy.Mode.WORKSPACE_WRITE);
        Path outsideTarget = Files.writeString(outside.resolve("escape-target.txt"), "外部");
        Path link = Files.createSymbolicLink(workspace.resolve("link"),
                outsideTarget.toAbsolutePath());
        assertFalse(policy.contains(link), "symlink 越界应被识别为 workspace 外");
    }

    @Test
    void traversalDotDotDetectedAsOutside() throws IOException {
        WorkspacePolicy policy = policy(WorkspacePolicy.Mode.WORKSPACE_WRITE);
        Path traversal = workspace.resolve("..").resolve("outside-file.txt");
        assertFalse(policy.contains(traversal), "`..` 穿越应落在 workspace 外");
    }

    @Test
    void relativePathResolvedAgainstWorkspaceRoot() throws IOException {
        WorkspacePolicy policy = policy(WorkspacePolicy.Mode.WORKSPACE_WRITE);
        Path relative = Path.of("relative-file.txt");
        assertTrue(policy.contains(relative), "相对路径按 workspace 根解析落在内");
    }

    @Test
    void nonExistentPathUnderWorkspaceContained() throws IOException {
        // 不存在的路径（如 write 将创建的新文件）：按规范化路径判定
        WorkspacePolicy policy = policy(WorkspacePolicy.Mode.WORKSPACE_WRITE);
        Path target = workspace.resolve("new-file.txt");
        assertTrue(policy.contains(target), "workspace 内新文件包含性成立");
    }

    @Test
    void setModeChangesSubsequentDecisions() throws IOException {
        // /permission 运行时切档：判定随最新档位变化（重启回 yml 缺省的持久化不在策略层）
        WorkspacePolicy policy = policy(WorkspacePolicy.Mode.WORKSPACE_WRITE);
        Path inWs = Files.writeString(workspace.resolve("a.txt"), "内容");
        assertEquals(WorkspacePolicy.Decision.ALLOW, policy.decide("write", inWs));
        policy.setMode(WorkspacePolicy.Mode.READ_ONLY);
        assertEquals(WorkspacePolicy.Decision.ASK, policy.decide("write", inWs), "切 read-only 后写转 ask");
        policy.setMode(WorkspacePolicy.Mode.DANGER_FULL_ACCESS);
        assertEquals(WorkspacePolicy.Decision.ALLOW, policy.decide("bash", null), "切 danger 后 bash 放行");
    }

    @Test
    void unresolvedWritePathDefaultsToAsk() throws IOException {
        WorkspacePolicy policy = policy(WorkspacePolicy.Mode.WORKSPACE_WRITE);
        assertEquals(WorkspacePolicy.Decision.ASK, policy.decide("write", null),
                "path 解析失败保守 ask（不抛异常）");
        assertEquals(WorkspacePolicy.Decision.ASK, policy.decide("edit", null));
    }

    @Test
    void networkReadTierMatrix() throws IOException {
        // 网络读三档（M20，ADR-0021 决策 8）：read-only 档不出网边界一律 ask；
        // workspace-write（默认档）与 danger 档放行
        // 用 NAME 常量钉住契约：工具改名而档位集合漏改时在此炸出（生产侧字面量避免 fs→web 包依赖成环）
        String fetch = dev.duo.harness.tools.web.WebFetchTool.NAME;
        String search = dev.duo.harness.tools.web.WebSearchTool.NAME;
        WorkspacePolicy readOnly = policy(WorkspacePolicy.Mode.READ_ONLY);
        assertEquals(WorkspacePolicy.Decision.ASK, readOnly.decide(fetch, null),
                "read-only 档联网 ask");
        assertEquals(WorkspacePolicy.Decision.ASK, readOnly.decide(search, null),
                "read-only 档联网 ask");
        WorkspacePolicy wsWrite = policy(WorkspacePolicy.Mode.WORKSPACE_WRITE);
        assertEquals(WorkspacePolicy.Decision.ALLOW, wsWrite.decide(fetch, null),
                "默认档联网放行");
        assertEquals(WorkspacePolicy.Decision.ALLOW, wsWrite.decide(search, null),
                "默认档联网放行");
        WorkspacePolicy danger = policy(WorkspacePolicy.Mode.DANGER_FULL_ACCESS);
        assertEquals(WorkspacePolicy.Decision.ALLOW, danger.decide("web_search", null));
        // 未知工具保守 ask 不回归
        assertEquals(WorkspacePolicy.Decision.ASK, wsWrite.decide("some_future_tool", null));
    }
}
