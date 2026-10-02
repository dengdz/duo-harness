package dev.duo.harness.session;

import dev.duo.harness.core.api.PluginException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坏行容错（M34 工单 02，BUG-20261002-07）：会话 JSONL 含坏行时 load 跳过并计数、
 * 结构性损坏保持 fail-loud 但锁必释放、导出对坏行计数明示——三症状（加载抛异常 +
 * 锁泄漏不可再入 + 导出静默零事件）的回归锁。
 */
class CorruptSessionToleranceTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：CorruptSessionToleranceTest —— 坏行容错：load 跳过计数、"
                + "结构损坏锁必释放、导出明示（3 用例） ===");
    }

    @TempDir
    Path tempDir;

    /** 追加字节（可读性：测试内多次拼接坏行/好行；CREATE 兜底首写）。 */
    private static void append(Path file, String content) throws Exception {
        Files.writeString(file, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    void loadSkipsCorruptLinesAndCountsThem() throws Exception {
        // BUG-20261002-07 症状①回归锁：好事件夹坏行 → load 返回（不抛），
        // 好事件全保留、坏行逐条计数——「跳过并标注」而非整文件抛异常
        Path file = tempDir.resolve("20260101-000000-b9f0.jsonl");
        append(file, "{\"type\":\"user/message\",\"at\":1,\"text\":\"第一条\"}\n");
        append(file, "{\"type\":\"assistant/message\",\"at\":2,\"text\":\"回复\"}\n");
        append(file, "这行不是JSON垃圾{{{\n");
        append(file, "{\"type\":\"user/message\",\"at\":3,\"text\":\"第二条\"}\n");
        append(file, "{\"type\":\"user/messa\n");

        Session session = Session.load(file);
        try {
            assertAll(
                    () -> assertEquals(3, session.events().size(), "好事件全保留（2 条消息 + 1 条助手回复）"),
                    () -> assertEquals(2, session.skippedCorruptLines(), "坏行逐条计数"),
                    () -> assertEquals("第一条", session.events().get(0).text(), "首条完好"),
                    () -> assertEquals("第二条", session.events().get(2).text(), "坏行之后的好事件仍在（跳过不断流）"));
        } finally {
            session.close();
        }
    }

    @Test
    void structuralCorruptionStillFailsLoudButReleasesLock() throws Exception {
        // BUG-20261002-07 症状②回归锁：结构性损坏（版本头不在首行）保持 fail-loud，
        // 但异常路径锁必释放——修复前 release-catch 只捕 IOException，RuntimeException
        // 穿透逃逸导致锁泄漏（后续访问 409 占用死锁）
        Path file = tempDir.resolve("20260101-000000-b9f1.jsonl");
        append(file, "{\"type\":\"user/message\",\"at\":1,\"text\":\"头前事件\"}\n");
        append(file, "{\"type\":\"session\",\"at\":2,\"version\":1,\"cwd\":\"/tmp\"}\n");

        assertThrows(PluginException.class, () -> Session.load(file), "结构损坏保持 fail-loud");
        assertFalse(Session.isOccupied(file), "加载失败后锁必释放（不留半开状态）");
    }

    @Test
    void exportAnnotatesSkippedCorruptLines() throws Exception {
        // BUG-20261002-07 症状③回归锁：导出对坏行计数明示——修复前导出对坏行会话
        // 静默产出（事件计数与正文均失真），交付物不可信
        Path file = tempDir.resolve("20260101-000000-b9f2.jsonl");
        append(file, "{\"type\":\"user/message\",\"at\":1,\"text\":\"第一条\"}\n");
        append(file, "垃圾行\n");
        Session session = Session.load(file);
        try {
            String md = SessionExport.markdown(session);
            assertTrue(md.contains("坏行"), "导出应提及坏行: " + md.substring(0, Math.min(400, md.length())));
            assertTrue(md.contains("1"), "导出应含坏行计数");
        } finally {
            session.close();
        }
    }
}
