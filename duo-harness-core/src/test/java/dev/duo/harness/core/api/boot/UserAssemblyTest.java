package dev.duo.harness.core.api.boot;

import dev.duo.harness.core.api.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 S1（boot 全链路）用例：用户装配可写事实源（ADR-0037 工单 03）——
 * 首启物化种子（文件 + 指纹在位）、物化文件为唯一装载来源（编辑即生效，种子
 * 不再覆盖）、升级漂移只记日志不阻断、重复 ensure 幂等。DUO_HOME 经
 * {@code duo.home} 系统属性注入临时目录。
 */
class UserAssemblyTest {

    private static final String SEED = "/boot/user-assembly-seed.yml";
    private static final String GREETER_FQCN = "dev.duo.harness.core.api.boot.BootTest$GreeterPlugin";

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：UserAssemblyTest —— 用户装配可写事实源：首启物化、"
                + "文件唯一来源、漂移不阻断、幂等（4 用例） ===");
    }

    @TempDir
    Path tempHome;

    @BeforeEach
    void 注入Home() {
        System.setProperty(DuoHome.PROP_OVERRIDE, tempHome.toString());
    }

    @AfterEach
    void 清理Home() {
        System.clearProperty(DuoHome.PROP_OVERRIDE);
    }

    // === 用例 ===

    @Test
    void freshHomeMaterializesSeedWithMarkAndBoots() throws IOException {
        Path assembly = Boot.ensureUserAssembly(SEED);

        // 文件与指纹在位，内容与种子一致
        assertTrue(Files.isRegularFile(assembly));
        assertEquals(tempHome.resolve("plugins.yml"), assembly);
        assertTrue(Files.readString(assembly).contains(GREETER_FQCN));
        Path mark = tempHome.resolve("plugins.yml.seed");
        assertTrue(Files.isRegularFile(mark), "种子指纹应在物化时落盘");

        // 物化文件可直接装载激活
        Context root = Boot.from(assembly);
        assertTrue(root.hasService("greeter"));
        root.dispose();
    }

    @Test
    void materializedFileIsSoleSourceSeedNoLongerWins() throws Exception {
        Path first = Boot.ensureUserAssembly(SEED);
        Context firstTree = Boot.from(first);
        assertTrue(firstTree.hasService("greeter"));
        firstTree.dispose();

        // 部署者编辑物化文件（greeter 停用）——再启动以文件为准，种子不回灌
        Files.writeString(first, """
                plugins:
                  - id: greeter
                    name: %s
                    disabled: true
                """.formatted(GREETER_FQCN), StandardCharsets.UTF_8);

        Context secondTree = Boot.from(Boot.ensureUserAssembly(SEED));
        assertFalse(secondTree.hasService("greeter"), "编辑物化文件后种子不得回灌");
        secondTree.dispose();
    }

    @Test
    void seedDriftIsLoggedNotFatal() throws IOException {
        Path first = Boot.ensureUserAssembly(SEED);
        // 篡改指纹模拟升级漂移（内置种子演进 → 指纹不一致）
        Files.writeString(tempHome.resolve("plugins.yml.seed"), "deadbeef");

        Path second = Boot.ensureUserAssembly(SEED);

        // 漂移只记日志提示对账，不阻断（返回同一文件路径，树照常装载）
        assertEquals(first, second);
        Context root = Boot.from(second);
        assertTrue(root.hasService("greeter"));
        root.dispose();
    }

    @Test
    void repeatedEnsureIsIdempotent() throws IOException {
        Path first = Boot.ensureUserAssembly(SEED);
        byte[] content = Files.readAllBytes(first);

        Path second = Boot.ensureUserAssembly(SEED);

        assertEquals(first, second);
        assertTrue(java.util.Arrays.equals(content, Files.readAllBytes(second)),
                "重复 ensure 不得重写文件（部署者编辑不被冲掉）");
    }
}
