package dev.duo.harness.core.api.boot;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * cwd 取值源用例（M28 工单 08）：path() 与 text() 即 JVM 进程工作目录的两种形态——
 * 与逐处 {@code Path.of(System.getProperty("user.dir"))} 的既有行为逐字一致。
 */
class CwdTest {

    @Test
    void pathMatchesProcessProperty() {
        assertEquals(Path.of(System.getProperty("user.dir")), Cwd.path(),
                "path() 与既有取值形态逐字一致");
    }

    @Test
    void textMatchesProcessProperty() {
        assertEquals(System.getProperty("user.dir"), Cwd.text(),
                "text() 与既有字符串取值一致");
    }
}
