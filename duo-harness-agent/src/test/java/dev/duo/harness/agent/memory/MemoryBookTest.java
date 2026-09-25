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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆本读载用例（M25 工单 02）：缺席/空白静默降级（无记忆即无注入）、
 * 现读语义、预算超限截尾、IO 异常不破坏读路径。
 */
class MemoryBookTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：MemoryBookTest —— 记忆本读载与写入：静默降级、现读、"
                + "预算截尾、append（10 用例） ===");
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
        // 路径指向目录——readString 必抛 IO 异常，读路径收敛为 null
        // （记忆故障不破坏对话轮）
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

    @Test
    void append到不存在的文件即创建() throws IOException {
        Path file = tempDir.resolve("sub").resolve("MEMORY.md");
        new MemoryBook(file, 1024).append("- 记住：构建用 Maven");
        assertEquals("- 记住：构建用 Maven\n", Files.readString(file),
                "缺席文件首次写入即创建（含父目录）且以换行收尾");
    }

    @Test
    void append到已有内容自动补换行() throws IOException {
        Path file = tempDir.resolve("MEMORY.md");
        Files.writeString(file, "- 第一条");  // 末尾无换行（用户手改常见形态）
        MemoryBook book = new MemoryBook(file, 1024);
        book.append("- 第二条");
        assertEquals("- 第一条\n- 第二条\n", Files.readString(file),
                "末尾无换行时先补再追加——两条不粘连");
        book.append("- 第三条");
        assertEquals("- 第一条\n- 第二条\n- 第三条\n", Files.readString(file),
                "已有换行收尾时直接追加");
    }

    @Test
    void append空白条目拒绝() {
        MemoryBook book = new MemoryBook(tempDir.resolve("MEMORY.md"), 1024);
        assertThrows(IllegalArgumentException.class, () -> book.append("   "),
                "空白条目错误前移（不该产出空行记忆）");
        assertThrows(IllegalArgumentException.class, () -> book.append(null),
                "null 条目错误前移");
    }

    @Test
    void append后读路径即见与用户手改不互吞() throws IOException {
        Path file = tempDir.resolve("MEMORY.md");
        MemoryBook book = new MemoryBook(file, 1024);
        book.append("- 模型写的");
        Files.writeString(file, "- 用户手改的\n- 模型写的\n");  // 用户重写文件
        book.append("- 模型又写的");
        String content = Files.readString(file);
        assertTrue(content.contains("- 用户手改的"),
                "用户手改条目保留（append 不覆写全量）: " + content);
        assertTrue(content.contains("- 模型又写的"), "模型追加生效: " + content);
        assertTrue(book.read().contains("- 用户手改的"), "读路径现读见合并结果");
    }
}
