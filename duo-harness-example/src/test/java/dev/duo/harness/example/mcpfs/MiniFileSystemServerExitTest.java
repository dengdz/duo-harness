package dev.duo.harness.example.mcpfs;

import dev.duo.harness.example.DemoMain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子进程自退回归（进程泄漏修复）：stdin 关闭（父 JVM 退出或被强杀的必然后果）
 * 后 MiniFileSystemServer 必须在限期内自行退出——修复前 main 以
 * {@code sleep(MAX_VALUE)} 保活，父进程异常终止即留孤儿进程（实测驻留 >90 分钟）。
 */
class MiniFileSystemServerExitTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：MiniFileSystemServerExitTest —— stdin 关闭即自退，"
                + "父 JVM 异常终止不留孤儿（1 用例） ===");
    }

    @Test
    void stdin关闭即自行退出() throws Exception {
        Path root = Files.createTempDirectory("duo-mcpfs-exit");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", DemoMain.subprocessClasspath(),
                MiniFileSystemServer.class.getName(), root.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        // 关闭 stdin：EOF 闸门放行，子进程自行退出，不依赖父进程显式销毁
        process.getOutputStream().close();

        assertTrue(process.waitFor(60, TimeUnit.SECONDS),
                "stdin 关闭后子进程应在限期内自退（未自退即孤儿进程回归）");
        assertEquals(0, process.exitValue(), "自退应以 0 退出");
    }
}
