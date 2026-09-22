package dev.duo.harness.agent.fileref;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * 工作区路径索引防护矩阵（M21 工单 07）：maxEntries 截断、排除目录、目录 symlink
 * 不跟随、子树不可读贡献 0 候选、越界返空。夹具 @TempDir 代码生成，零真实工作区。
 */
class FileReferenceIndexTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：FileReferenceIndexTest —— 路径索引防护矩阵（6 用例） ===");
    }

    private List<String> paths(int maxEntries) throws Exception {
        FileReferenceIndex index = new FileReferenceIndex(dir, maxEntries,
                dev.duo.harness.tools.fs.IgnorePolicy.load(dir));
        return index.buildIndex().stream().map(FileReferenceIndex.Candidate::path)
                .collect(Collectors.toList());
    }

    @Test
    void collectsFilesAndDirectoriesUnderRoot() throws Exception {
        Files.writeString(dir.resolve("README.md"), "hi");
        Files.createDirectories(dir.resolve("src/main"));
        Files.writeString(dir.resolve("src/main/App.java"), "x");
        List<String> out = paths(1000);
        assertTrue(out.contains("README.md"));
        assertTrue(out.contains("src"));
        assertTrue(out.contains("src/main/App.java"));
    }

    @Test
    void gitignoreTargetsExcludedSameAsTools() throws Exception {
        // 同口径（M23 工单 08）：.gitignore 忽略的目标不进补全索引——
        // 补全看得到的 grep 一定看得到（同一判定器同口径）
        Files.writeString(dir.resolve(".gitignore"), "generated.txt\n");
        Files.writeString(dir.resolve("generated.txt"), "x");
        Files.writeString(dir.resolve("handmade.txt"), "x");
        FileReferenceIndex index = new FileReferenceIndex(dir, 1000,
                dev.duo.harness.tools.fs.IgnorePolicy.load(dir));
        List<String> paths = index.buildIndex().stream()
                .map(FileReferenceIndex.Candidate::path).collect(Collectors.toList());
        assertTrue(paths.contains("handmade.txt"), "可见文件在索引");
        assertTrue(!paths.contains("generated.txt"), ".gitignore 目标不进索引: " + paths);
    }

    @Test
    void excludedDirsAreSkipped() throws Exception {
        Files.createDirectories(dir.resolve(".git/objects"));
        Files.createDirectories(dir.resolve("node_modules/pkg"));
        Files.createDirectories(dir.resolve("target/classes"));
        Files.writeString(dir.resolve("src.txt"), "x");
        List<String> out = paths(1000);
        assertTrue(out.contains("src.txt"));
        assertTrue(out.stream().noneMatch(p -> p.contains(".git") || p.contains("node_modules")
                || p.contains("target")));
    }

    @Test
    void maxEntriesTruncates() throws Exception {
        for (int i = 0; i < 10; i++) {
            Files.writeString(dir.resolve("f" + i + ".txt"), "x");
        }
        assertEquals(3, paths(3).size());
    }

    @Test
    void directorySymlinkListedButNotFollowed() throws Exception {
        Path outside = Files.createTempDirectory("fileref-outside");
        Files.writeString(outside.resolve("secret.txt"), "outside");
        Files.createDirectories(dir.resolve("real"));
        Files.writeString(dir.resolve("keep.txt"), "x");
        Files.createSymbolicLink(dir.resolve("link"), outside);
        List<String> out = paths(1000);
        assertTrue(out.contains("link")); // 候选在列（模型可提及）
        assertTrue(out.stream().noneMatch(p -> p.contains("secret")), // 不下钻 symlink 内容
                "symlink 目录内容不应入索引: " + out);
    }

    @Test
    void unreadableSubtreeContributesNothing() throws Exception {
        Files.writeString(dir.resolve("visible.txt"), "x");
        Path locked = dir.resolve("locked");
        Files.createDirectories(locked);
        Files.writeString(locked.resolve("hidden.txt"), "x");
        assumeFalse("root".equals(System.getProperty("user.name")), "root 下权限测试无意义");
        try {
            Files.setPosixFilePermissions(locked,
                    java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
            List<String> out = paths(1000);
            assertTrue(out.contains("visible.txt"));
            assertTrue(out.contains("locked")); // 目录本身在列
            assertTrue(out.stream().noneMatch(p -> p.contains("hidden")), // 内容贡献 0 候选
                    "不可读子树内容不应入索引: " + out);
        } finally {
            Files.setPosixFilePermissions(locked,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void resolveOutsideWorkspaceIsNull() {
        FileReferenceIndex index = new FileReferenceIndex(dir, 1000,
                dev.duo.harness.tools.fs.IgnorePolicy.load(dir));
        assertEquals(dir.resolve("a.txt").normalize(), index.resolve("a.txt"));
        assertNull(index.resolve("../outside.txt"));
        assertNull(index.resolve("/etc/passwd"));
    }
}
