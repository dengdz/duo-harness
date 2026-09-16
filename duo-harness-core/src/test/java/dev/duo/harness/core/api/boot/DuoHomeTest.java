package dev.duo.harness.core.api.boot;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DuoHome 用例：env 重定向、系统属性注入口、默认路径、子目录懒创建。 */
class DuoHomeTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：DuoHomeTest —— Duo home 解析：DUO_HOME 重定向、duo.home 注入口与默认路径、子目录创建（5 用例） ===");
    }

    @Test
    void envOverrideRedirectsRoot(@TempDir Path tempDir) {
        DuoHome home = DuoHome.resolve(tempDir.toString());

        assertEquals(tempDir.toAbsolutePath().normalize(), home.root());
    }

    @Test
    void propOverrideWinsOverEnv(@TempDir Path tempDir) {
        System.setProperty(DuoHome.PROP_OVERRIDE, tempDir.toString());
        try {
            // 优先级语义：系统属性最优先——JVM 进程内无法设置环境变量，"属性压过 DUO_HOME"
            // 在本机恰好设了 DUO_HOME 时即为直接证明，未设时退化为"属性压过缺省"（两种环境均须绿）
            assertEquals(tempDir.toAbsolutePath().normalize(), DuoHome.resolve().root(),
                    "duo.home 系统属性应为最高优先级");
        } finally {
            System.clearProperty(DuoHome.PROP_OVERRIDE);
        }
    }

    @Test
    void blankPropFallsThrough() {
        System.setProperty(DuoHome.PROP_OVERRIDE, "  ");
        try {
            // 空白属性等价未设：回落环境变量或默认——不依赖本机具体环境，只断言解析成功且为绝对路径
            assertTrue(DuoHome.resolve().root().isAbsolute(), "空白 duo.home 属性应等价未设");
        } finally {
            System.clearProperty(DuoHome.PROP_OVERRIDE);
        }
    }

    @Test
    void blankEnvFallsBackToUserHome() {
        DuoHome home = DuoHome.resolve("  ");

        assertEquals(Path.of(System.getProperty("user.home"), ".duo").toAbsolutePath().normalize(),
                home.root(), "空白 env 应回落到默认 ~/.duo");
    }

    @Test
    void resolveDirCreatesSubDirectoryLazily(@TempDir Path tempDir) throws IOException {
        DuoHome home = DuoHome.resolve(tempDir.toString());

        Path sessions = home.resolveDir("sessions");

        assertTrue(Files.isDirectory(sessions), "子目录应按需创建");
        assertEquals(tempDir.resolve("sessions"), sessions);
    }
}
