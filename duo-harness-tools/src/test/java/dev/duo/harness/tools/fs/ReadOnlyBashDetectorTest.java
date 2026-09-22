package dev.duo.harness.tools.fs;

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
 * 只读 bash 判定器用例（M24 工单 03，ADR-0026 决策二）：allowAnyArg 全表覆盖、
 * git 四件套与信任分类（.git 存在性 / -C 逃逸拒绝）、复合与动态语法 fail-closed、
 * 包装命令不剥（sudo/env/路径前缀）、未知命令 undefined→非只读。
 */
class ReadOnlyBashDetectorTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ReadOnlyBashDetectorTest —— 只读判定：allowAnyArg 全表、git 四件套"
                + "与信任分类、复合语法 fail-closed（9 用例） ===");
    }

    /** 带信任根（.git 存在）的判定器。 */
    private ReadOnlyBashDetector trusted() {
        try {
            Files.createDirectories(tempDir.resolve(".git"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new ReadOnlyBashDetector(tempDir);
    }

    @Test
    void allowAnyArgTableFullyReadOnly() {
        // 全表逐条（经访问器与判定同源，命令表增删免双处同步）：不带参与带参都只读
        ReadOnlyBashDetector detector = trusted();
        for (String word : ReadOnlyBashDetector.allowAnyArgCommands()) {
            assertTrue(detector.isReadOnlyBash(word), "表内命令应只读: " + word);
            assertTrue(detector.isReadOnlyBash(word + " --flags /some/path"),
                    "allowAnyArg：任意参数保持只读: " + word);
        }
        assertTrue(ReadOnlyBashDetector.allowAnyArgCommands().size() >= 30, "首期口径约 30 条");
    }

    @Test
    void gitFourSubcommandsReadOnlyWithTrustRoot() {
        ReadOnlyBashDetector detector = trusted();
        assertTrue(detector.isReadOnlyBash("git status"));
        assertTrue(detector.isReadOnlyBash("git log --oneline -5"));
        assertTrue(detector.isReadOnlyBash("git diff --stat"));
        assertTrue(detector.isReadOnlyBash("git show HEAD~1"));
        assertFalse(detector.isReadOnlyBash("git push"), "写子命令不在四件套");
        assertFalse(detector.isReadOnlyBash("git reset --hard"), "破坏子命令不在四件套");
        assertFalse(detector.isReadOnlyBash("git"), "裸 git 无子命令");
    }

    @Test
    void gitDashCEscapeRejectedByParse() {
        // git -C 逃逸口：第二词是全局旗标非四件套 → 解析层直接拒绝（fail-closed）
        ReadOnlyBashDetector detector = trusted();
        assertFalse(detector.isReadOnlyBash("git -C /anywhere status"));
        assertFalse(detector.isReadOnlyBash("git -C /etc log"));
    }

    @Test
    void gitTrustClassificationFailsClosedWithoutGitDir() throws Exception {
        // 信任分类：trust root 下无 .git → 四件套也非只读（运行时上下文缺失）
        Path plain = tempDir.resolve("plain");
        Files.createDirectories(plain);
        ReadOnlyBashDetector detector = new ReadOnlyBashDetector(plain);
        assertFalse(detector.isReadOnlyBash("git status"), "无 .git 的目录 git 不可信");

        Path project = tempDir.resolve("proj");
        Files.createDirectories(project.resolve(".git"));
        assertTrue(new ReadOnlyBashDetector(project).isReadOnlyBash("git status"), "有 .git 即可信");
    }

    @Test
    void compositeAndDynamicSyntaxFailClosed() {
        ReadOnlyBashDetector detector = trusted();
        assertFalse(detector.isReadOnlyBash("ls | wc -l"), "管道");
        assertFalse(detector.isReadOnlyBash("cat a > b"), "输出重定向");
        assertFalse(detector.isReadOnlyBash("grep foo < input"), "输入重定向");
        assertFalse(detector.isReadOnlyBash("ls && rm -rf /"), "顺序执行");
        assertFalse(detector.isReadOnlyBash("echo `id`"), "命令替换");
        assertFalse(detector.isReadOnlyBash("echo $HOME"), "变量展开");
        assertFalse(detector.isReadOnlyBash("ls; whoami"), "分号顺序");
        assertFalse(detector.isReadOnlyBash("grep foo &"), "后台");
    }

    @Test
    void wrappersNotStrippedFailClosed() {
        // 包装命令不剥（sudo/env/command/路径前缀的 env 赋值形态）——首词非表内即非只读
        ReadOnlyBashDetector detector = trusted();
        assertFalse(detector.isReadOnlyBash("sudo ls /tmp"), "sudo 包装");
        assertFalse(detector.isReadOnlyBash("env ls"), "env 包装（可携任意执行面）");
        assertFalse(detector.isReadOnlyBash("command ls"), "command 包装");
        assertFalse(detector.isReadOnlyBash("FOO=bar ls"), "env 赋值前缀");
        assertFalse(detector.isReadOnlyBash("xargs rm < list"), "xargs 执行面");
    }

    @Test
    void pathPrefixedCommandRejected() {
        // 路径前缀命令不认（防同名本地二进制借 basename 入白名单——疑罪从有）
        ReadOnlyBashDetector detector = trusted();
        assertFalse(detector.isReadOnlyBash("/bin/ls -la"), "绝对路径非裸名");
        assertFalse(detector.isReadOnlyBash("./cat x"), "相对路径非裸名");
        assertFalse(detector.isReadOnlyBash("/tmp/evil/ls"), "任意路径同名二进制");
        assertTrue(detector.isReadOnlyBash("ls -la"), "裸名照常只读");
    }

    @Test
    void unknownCommandAndBlankFailClosed() {
        ReadOnlyBashDetector detector = trusted();
        assertFalse(detector.isReadOnlyBash("makemiracle"), "未知命令 undefined→非只读");
        assertFalse(detector.isReadOnlyBash("ll -la"), "别名形态（未知首词）undefined→非只读");
        assertFalse(detector.isReadOnlyBash("rm -rf /"), "已知写命令");
        assertFalse(detector.isReadOnlyBash(""), "空串");
        assertFalse(detector.isReadOnlyBash("   "), "空白");
        assertFalse(detector.isReadOnlyBash(null), "null");
    }

    @Test
    void globsAndQuotesInArgsAllowed() {
        // 通配与引号是参数展开形态（ZCode 动态词口径不含路径名通配）——只读命令带之仍只读
        ReadOnlyBashDetector detector = trusted();
        assertTrue(detector.isReadOnlyBash("ls *.java"));
        assertTrue(detector.isReadOnlyBash("grep -rn \"todo text\" src/"));
        assertTrue(detector.isReadOnlyBash("wc -l 'my file.txt'"));
        assertEquals(ReadOnlyBashDetector.Verdict.READONLY, detector.verdict("ls src/*/"));
    }
}
