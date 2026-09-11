package dev.duo.harness.core.api.boot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DuoHome 用例：env 重定向、默认路径、子目录懒创建。 */
class DuoHomeTest {

    @Test
    void envOverrideRedirectsRoot(@TempDir Path tempDir) {
        DuoHome home = DuoHome.resolve(tempDir.toString());

        assertEquals(tempDir.toAbsolutePath().normalize(), home.root());
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
