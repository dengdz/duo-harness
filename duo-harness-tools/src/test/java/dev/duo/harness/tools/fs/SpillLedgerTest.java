package dev.duo.harness.tools.fs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 套件：SpillLedgerTest —— spill 登记簿（C2 工单 01）：装配级路径分配、
 * 跨装配并存回收隔离（P1 回归锁：任一装配停止不误删他方活 spill）、
 * 懒创建路径回收静默（3 用例）。
 */
class SpillLedgerTest {

    @TempDir
    Path tempDir;
    private String realDuoHome;

    @BeforeEach
    void setUp() {
        // spill 落 Duo home 临时区——测试用 duo.home 系统属性重定向（解析优先级第一），
        // 不污染真实 ~/.duo（先设后建：ledger 构造时解析目录）
        realDuoHome = System.getProperty("duo.home");
        System.setProperty("duo.home", tempDir.resolve("duo-home").toString());
    }

    @AfterEach
    void restoreDuoHome() {
        if (realDuoHome != null) {
            System.setProperty("duo.home", realDuoHome);
        } else {
            System.clearProperty("duo.home");
        }
    }

    @Test
    void nextAssignsUniquePathWithPidPrefix() {
        SpillLedger ledger = new SpillLedger();
        Path a = ledger.next("stdout");
        Path b = ledger.next("stdout");
        assertNotEquals(a, b, "两次分配不同路径");
        assertTrue(a.getFileName().toString()
                        .startsWith("bash-" + ProcessHandle.current().pid() + "-"),
                "文件名带进程号前缀（跨进程防碰撞）: " + a.getFileName());
    }

    /** 跨装配并存互不误删（P1 回归锁）：B 的插件树停止不删 A 的活 spill。 */
    @Test
    void disposeRemovesOnlyOwnedFilesCrossAssembly() throws IOException {
        SpillLedger ledgerA = new SpillLedger();
        // 同进程另一装配（Web 多会话 / 子代理树是一等场景）：同一共享目录、各自登记
        SpillLedger ledgerB = new SpillLedger();
        Path aFile = ledgerA.next("stdout");
        Path bFile = ledgerB.next("stdout");
        Files.createDirectories(aFile.getParent());
        Files.writeString(aFile, "装配 A 的活 spill");
        Files.writeString(bFile, "装配 B 的 spill");

        ledgerB.dispose(); // B 的插件树停止

        assertFalse(Files.exists(bFile), "B 停止回收自有 spill");
        assertTrue(Files.exists(aFile), "A 的活 spill 不被 B 的停止误删（P1 回归锁）");
    }

    /** 回收后登记清空；懒创建未落盘路径回收静默。 */
    @Test
    void disposeClearsLedgerSilentForLazyPaths() {
        SpillLedger ledger = new SpillLedger();
        Path lazy = ledger.next("stderr"); // 分配但未实际落盘（懒创建语义）
        ledger.dispose(); // deleteIfExists 对未落盘路径静默
        assertFalse(Files.exists(lazy));

        Path fresh = ledger.next("stdout");
        ledger.dispose();
        assertFalse(Files.exists(fresh), "dispose 后新分配照常登记并可回收");
    }
}
