package dev.duo.harness.tools.fs;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 忽略判定器语法矩阵（M23 工单 08，ADR-0025 决策三）：全常用子集逐项 +
 * last-match-wins 覆盖链 + 三源并集 + 坏行跳过。判定器为纯函数形态（给目录
 * 写规则文件 → 问 ignored），不依赖消费点遍历形态。
 */
class IgnorePolicyTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：IgnorePolicyTest —— .gitignore 忽略判定：全常用子集/覆盖链/"
                + "三源并集/坏行跳过（11 用例） ===");
    }

    @TempDir
    Path root;

    private IgnorePolicy policy() {
        return IgnorePolicy.load(root);
    }

    private void write(String rel, String content) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private boolean ignored(String relWorkspacePath) {
        return policy().ignored(root.resolve(relWorkspacePath), false);
    }

    private boolean ignoredDir(String relWorkspacePath) {
        return policy().ignored(root.resolve(relWorkspacePath), true);
    }

    @Test
    void basenamePatternMatchesAnyDepth() throws IOException {
        write(".gitignore", "*.log\n");
        assertTrue(ignored("a.log"), "根下命中");
        assertTrue(ignored("src/main/x.log"), "任意层命中");
        assertFalse(ignored("a.txt"), "非命中");
    }

    @Test
    void anchoredPatternOnlyAtBase() throws IOException {
        write(".gitignore", "/uploads\n");
        assertTrue(ignored("uploads"), "锚定命中根下");
        assertTrue(ignored("uploads/x.bin"), "锚定命中目录内文件");
        assertFalse(ignored("src/uploads"), "锚定不命中深层的同名路径");
    }

    @Test
    void dirOnlyRuleMatchesDirectoriesAndPrunesChildren() throws IOException {
        write(".gitignore", "logs/\n");
        assertTrue(ignoredDir("logs"), "目录命中");
        assertFalse(ignored("logs"), "同名文件不被目录限定规则命中");
        assertTrue(ignored("logs/x.txt"), "被忽略目录内文件随树忽略");
        // git 语义：父目录被忽略后，! 反选救不回内部文件
        write(".gitignore", "logs/\n!logs/keep.txt\n");
        assertTrue(ignored("logs/keep.txt"), "被忽略目录内反选无效");
    }

    @Test
    void negateReincludesMatchingFile() throws IOException {
        write(".gitignore", "*.log\n!keep.log\n");
        assertTrue(ignored("run.log"), "通配仍命中");
        assertFalse(ignored("keep.log"), "反选救回");
    }

    @Test
    void doubleStarForms() throws IOException {
        write(".gitignore", "**/tests\na/**/b\ndocs/**\n");
        assertTrue(ignored("a/tests"), "**/ 命中深层");
        assertTrue(ignored("tests"), "**/ 命中根下");
        assertTrue(ignored("a/b"), "a/**/b 零段命中");
        assertTrue(ignored("a/x/y/b"), "a/**/b 多段命中");
        assertTrue(ignored("docs/x.md"), "尾 /** 目录内一切");
        assertFalse(ignored("docs"), "尾 /** 不忽略目录自身");
        assertFalse(ignored("a/c"), "a/**/b 不命中旁路");
    }

    @Test
    void charClassQuestionAndEscape() throws IOException {
        write(".gitignore", "[ab].txt\nfile?.md\n\\!important\n");
        assertTrue(ignored("a.txt"), "字符类命中");
        assertFalse(ignored("c.txt"), "字符类外不命中");
        assertTrue(ignored("fileX.md"), "? 单字符");
        assertFalse(ignored("fileXY.md"), "? 不跨字符");
        assertTrue(ignored("!important"), "转义 ! 作字面");
    }

    @Test
    void negatedCharClassAndUnanchoredDirOnly() throws IOException {
        write(".gitignore", "[!a].txt\nlogs/\n");
        assertTrue(ignored("b.txt"), "取反字符类命中（非 a 开头）");
        assertFalse(ignored("a.txt"), "取反字符类不命中 a");
        assertTrue(ignoredDir("deep/logs"), "未锚定目录限定命中任意层的目录");
        assertTrue(ignored("deep/logs/x.txt"), "深层命中目录内文件随树忽略");
        assertFalse(ignored("deep/logs"), "目录限定对同名文件不命中（git 同义）");
    }

    @Test
    void badLinesSkippedSilently() throws IOException {
        write(".gitignore", "[unclosed\n*.tmp\n# 注释\n\n   \n");
        assertFalse(ignored("anything.txt"), "坏行不误伤");
        assertTrue(ignored("x.tmp"), "坏行不影响后续规则");
        assertFalse(ignoredDir("注释"), "注释行不产生规则");
    }

    @Test
    void deeperGitignoreOverridesShallower() throws IOException {
        write(".gitignore", "*.log\n");
        write("src/.gitignore", "!keep.log\nspecific.txt\n");
        assertTrue(ignored("run.log"), "根规则命中");
        assertFalse(ignored("src/keep.log"), "深层反选覆盖根规则");
        assertTrue(ignored("other/keep.log"), "深层规则只作用该子树");
        assertTrue(ignored("src/specific.txt"), "深层相对基命中");
        assertFalse(ignored("specific.txt"), "深层相对基不越子树");
    }

    @Test
    void infoExcludeAndHardcodedSources() throws IOException {
        write(".git/info/exclude", "secret-draft.md\n");
        assertTrue(ignored("secret-draft.md"), ".git/info/exclude 生效");
        assertTrue(ignored("node_modules/x.js"), "产物目录硬源");
        assertTrue(ignoredDir("target"), "构建目录硬源");
        assertTrue(ignored(".git/config"), "VCS 目录硬源");
        write(".gitignore", "!node_modules\n");
        assertTrue(ignored("node_modules/x.js"), "反选不救硬编码源（并集语义）");
    }

    @Test
    void outsideWorkspaceNotIgnored() throws IOException {
        assertFalse(policy().ignored(root.resolve("../outside.txt").normalize(), false),
                "workspace 外不判定（恒不忽略）");
    }

    @Test
    void trailingSlashEscapedLiteralAnchorForms() throws IOException {
        write(".gitignore", "/deep/dir/\nfoo\\ bar.txt\n");
        assertTrue(ignored("deep/dir/inner.txt"), "锚定目录限定组合");
        assertTrue(ignoredDir("deep/dir"), "锚定目录限定命中目录");
        assertFalse(ignored("deep/other.txt"), "锚定不外溢");
        assertTrue(ignored("foo bar.txt"), "转义空格字面命中");
    }
}
