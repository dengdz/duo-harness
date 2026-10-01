package dev.duo.harness.core.api.boot;

import dev.duo.harness.core.api.Context;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源装载通道用例（M30 工单 01）：Boot.fromResource 与 from(Path) 同语义——
 * 同一装载器、同一审计点名、同一失败回滚，仅配置来源不同（classpath 流 vs 文件路径）。
 * 「java -jar 即跑」的缺省装配装载正门（fat-jar 内资源 URI 非文件形态，文件化读取必炸）。
 */
class BootResourceTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：BootResourceTest —— 资源装载通道：与文件路径装载同语义、"
                + "缺失与坏结构点名、回调触发（4 用例） ===");
    }

    @TempDir
    Path tempDir;

    interface GreeterView {
        String greeter();
    }

    @Test
    void fromResourceLoadsSameSemanticsAsFilePath() throws Exception {
        // 同一输入双通道对拍：资源文本原样落盘为文件，文件装载 vs 资源装载同语义
        String resourceText;
        try (var in = getClass().getResourceAsStream("/boot/boot-resource-ok.yml")) {
            resourceText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path file = tempDir.resolve("same.yml");
        Files.writeString(file, resourceText);

        Context fromFile = Boot.from(file);
        Context fromResource = Boot.fromResource("/boot/boot-resource-ok.yml");

        assertEquals("你好", fromFile.as(GreeterView.class).greeter());
        assertEquals("你好", fromResource.as(GreeterView.class).greeter());
        fromFile.dispose();
        fromResource.dispose();
    }

    @Test
    void fromResourceRunsOnRootCreatedCallback() {
        AtomicInteger called = new AtomicInteger();
        Context root = Boot.fromResource("/boot/boot-resource-ok.yml",
                ctx -> called.incrementAndGet());
        assertEquals(1, called.get());
        root.dispose();
    }

    @Test
    void fromResourceMissingFailsAtReadConfig() {
        BootException e = assertThrows(BootException.class,
                () -> Boot.fromResource("/boot/no-such-assembly.yml"));
        assertEquals(BootException.Stage.READ_CONFIG, e.stage());
        assertTrue(e.getMessage().contains("/boot/no-such-assembly.yml"), e.getMessage());
    }

    @Test
    void fromResourceMalformedStructureFailsAtParseConfig() {
        BootException e = assertThrows(BootException.class,
                () -> Boot.fromResource("/boot/boot-resource-bad.yml"));
        assertEquals(BootException.Stage.PARSE_CONFIG, e.stage());
        // 错误点名携带 classpath 来源标签（与文件路径形态的路径点名同位）
        assertTrue(e.getMessage().contains("classpath:/boot/boot-resource-bad.yml"),
                e.getMessage());
    }
}
