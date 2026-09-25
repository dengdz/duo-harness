package dev.duo.harness.agent.memory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆本读载用例（M25 工单 02）：缺席/空白静默降级（无记忆即无注入）、
 * 现读语义、预算超限截尾、IO 异常不破坏读路径。
 */
class MemoryBookTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：MemoryBookTest —— 记忆本读载：静默降级、现读、预算截尾（6 用例） ===");
    }

    @TempDir
    Path tempDir;

    @Test
    void 缺席文件静默降级() {
        assertNull(new MemoryBook(tempDir.resolve("MEMORY.md"), 1024).read(),
                "文件不存在时 read() 应为 null（无注入、无报错）");
    }

    @Test
    void 空白内容静默降级() throws IOException {
        Path file = tempDir.resolve("MEMORY.md");
        Files.writeString(file, "   \n  \n");
        assertNull(new MemoryBook(file, 1024).read(), "空白记忆本等同未启用——不应注入空段");
    }

    @Test
    void 有内容现读() throws IOException {
        Path file = tempDir.resolve("MEMORY.md");
        Files.writeString(file, "- 用户偏好中文回复\n");
        assertEquals("- 用户偏好中文回复", new MemoryBook(file, 1024).read());
        // 现读语义：改文件再读即见新内容（不缓存启动快照）
        Files.writeString(file, "- 改过的记忆\n");
        assertEquals("- 改过的记忆", new MemoryBook(file, 1024).read());
    }

    @Test
    void 超预算截尾并标注() throws IOException {
        Path file = tempDir.resolve("MEMORY.md");
        Files.writeString(file, "x".repeat(200));
        String read = new MemoryBook(file, 100).read();
        assertTrue(read.length() < 200, "超预算应截断: " + read.length());
        assertTrue(read.contains("已截断"), "截断应带标注让模型可知: " + read);
    }

    @Test
    void 读取异常静默降级不抛() {
        // 路径指向目录——readString 必抛 IO 异常，读路径收敛为 null（记忆故障不破坏对话轮）
        assertNull(new MemoryBook(tempDir, 1024).read(), "IO 异常应静默降级为 null");
    }

    @Test
    void metaUser段组装与降级() throws IOException {
        Path file = tempDir.resolve("MEMORY.md");
        MemoryBook absent = new MemoryBook(file, 1024);
        assertNull(absent.metaUserSection(), "无记忆 → 无 meta_user 段");

        Files.writeString(file, "- 记住项目用 Maven\n");
        String section = new MemoryBook(file, 1024).metaUserSection();
        assertTrue(section.startsWith("<memory>"), "meta_user 段以 memory 标签包裹: " + section);
        assertTrue(section.contains("- 记住项目用 Maven"), "段内含记忆内容: " + section);
        assertTrue(section.contains("以用户为准"), "段内含时效免责语: " + section);
        assertTrue(section.endsWith("</memory>"), "段以闭合标签收尾: " + section);
        assertFalse(section.contains("null"), "不出现字面 null");
    }
}
