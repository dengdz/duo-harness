package dev.duo.harness.example;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行序契约预检用例（M27 工单 05，扫描册 H-05）：cli 行的 apply 即 REPL 主循环，
 * 其后所有行在 REPL 退出前不装载——cli 与 web 同在册时 web 行后置必须在 boot 前
 * 点名（2026-09-27 用户验收实测的静默缺席形态，就此显式化）。
 */
class DuoMainRowOrderContractTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：DuoMainRowOrderContractTest —— 行序契约预检：cli 后置 web "
                + "在 boot 前点名，文件路径与资源流两通道同扫描核（4 用例） ===");
    }

    @Test
    void webRowAfterCliRowFailsBeforeBoot(@TempDir Path tempDir) throws Exception {
        Path yml = tempDir.resolve("swapped.yml");
        Files.writeString(yml, """
                plugins:
                  - id: tools
                    name: dev.duo.harness.tools.ToolsPlugin

                  - id: cli
                    name: dev.duo.harness.cli.CliPlugin

                  - id: web
                    name: dev.duo.harness.web.WebPlugin
                """);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> DuoMain.validatePresenterRowOrder(yml));
        assertTrue(thrown.getMessage().contains("行序契约"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("REPL 主循环"), thrown.getMessage());
    }

    @Test
    void correctOrderAndPresenterFreeAssembliesPass(@TempDir Path tempDir) throws Exception {
        Path good = tempDir.resolve("good.yml");
        Files.writeString(good, """
                plugins:
                  - id: web
                    name: dev.duo.harness.web.WebPlugin

                  - id: cli
                    name: dev.duo.harness.cli.CliPlugin
                """);
        assertDoesNotThrow(() -> DuoMain.validatePresenterRowOrder(good));

        Path noPresenters = tempDir.resolve("no-presenters.yml");
        Files.writeString(noPresenters, """
                plugins:
                  - id: tools
                    name: dev.duo.harness.tools.ToolsPlugin
                """);
        assertDoesNotThrow(() -> DuoMain.validatePresenterRowOrder(noPresenters));
    }

    @Test
    void resourceVariantValidatesRowOrder() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> DuoMain.validatePresenterRowOrder("/roworder/swapped.yml"));
        assertTrue(thrown.getMessage().contains("行序契约"), thrown.getMessage());
        assertDoesNotThrow(() -> DuoMain.validatePresenterRowOrder("/roworder/ok.yml"));
    }

    @Test
    void defaultAssemblyResourcePassesPrecheck() {
        // 真实缺省装配（agent-demo.yml：web 先于 cli）经资源流预检通过——
        // 缺省分支 fat-jar 形态的第一道闸（装载归 Boot.fromResource，01 工单）
        assertDoesNotThrow(() -> DuoMain.validatePresenterRowOrder("/agent-demo.yml"));
    }
}
