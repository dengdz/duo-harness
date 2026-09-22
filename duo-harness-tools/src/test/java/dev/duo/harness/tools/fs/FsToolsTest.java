package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** fs 文件工具五件（M12-02）：读写编辑检索的工具级行为与边界。 */
class FsToolsTest {

    @TempDir
    Path tempDir;

    private WorkspacePolicy policy;
    private ReadGate gate;
    private Path ws;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：FsToolsTest —— fs 文件工具五件：read 三帽窗口、"
                + "write 原子+闸门、edit 四态失败、glob/grep 检索（29 用例） ===");
    }

    @BeforeEach
    void setUp() throws IOException {
        ws = tempDir.resolve("ws");
        Files.createDirectories(ws);
        policy = new WorkspacePolicy(ws, WorkspacePolicy.Mode.WORKSPACE_WRITE);
        gate = new ReadGate();
    }

    private static ToolExecution exec(JsonNode args) {
        return new ToolExecution("test", args);
    }

    private static JsonNode json(String json) {
        try { return new ObjectMapper().readTree(json); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String str(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---- read ----

    @Test
    void readBasicLinesWithNumbers() throws IOException {
        Path file = Files.writeString(ws.resolve("a.txt"), "第一行\n第二行\n第三行\n");
        FsReadTool tool = new FsReadTool(policy, gate);
        String result = tool.execute(exec(json("{\"path\":\"" + str(file.toString()) + "\"}")));
        assertTrue(result.contains("1: 第一行"));
        assertTrue(result.contains("3: 第三行"));
        assertTrue(result.contains("End of file - total 3 lines"));
    }

    @Test
    void readBinaryFileRejected() throws IOException {
        Path file = ws.resolve("bin.bin");
        Files.write(file, new byte[]{0x00, 0x01, 0x00, 0x02});
        FsReadTool tool = new FsReadTool(policy, gate);
        String result = tool.execute(exec(json("{\"path\":\"" + str(file.toString()) + "\"}")));
        assertTrue(result.contains("二进制"), result);
    }

    @Test
    void readOffsetAndLimitWindow() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) sb.append("line-").append(i).append('\n');
        Path file = Files.writeString(ws.resolve("big.txt"), sb.toString());
        FsReadTool tool = new FsReadTool(policy, gate);
        String result = tool.execute(exec(json(
                "{\"path\":\"" + str(file.toString()) + "\",\"offset\":3,\"limit\":3}")));
        assertTrue(result.contains("3: line-3"), result);
        assertTrue(result.contains("5: line-5"), result);
        assertTrue(result.contains("Showing lines 3-5 of 10"), result);
    }

    @Test
    void readTotalBytesCapTruncates() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 600; i++) sb.append("x".repeat(100)).append('\n');
        Path file = Files.writeString(ws.resolve("wide.txt"), sb.toString());
        FsReadTool tool = new FsReadTool(policy, gate);
        String result = tool.execute(exec(json("{\"path\":\"" + str(file.toString()) + "\"}")));
        assertTrue(result.contains("Output capped"), "50KiB 总帽截断: " + result.length());
        assertTrue(result.contains("Use offset="), result);
    }

    @Test
    void readLongLineTruncatedAt2000() throws IOException {
        Path file = Files.writeString(ws.resolve("long.txt"), "b".repeat(2000) + "TAIL_MARKER\n");
        FsReadTool tool = new FsReadTool(policy, gate);
        String result = tool.execute(exec(json("{\"path\":\"" + str(file.toString()) + "\"}")));
        assertTrue(result.contains("…"), result);
        assertFalse(result.contains("TAIL_MARKER"), "单行 2000 字符截断");
    }

    // ---- write ----

    @Test
    void writeNewFileSucceeds() throws IOException {
        FsWriteTool tool = new FsWriteTool(policy, gate);
        String result = tool.execute(exec(json(
                "{\"path\":\"" + str(ws.resolve("new.txt").toString()) + "\",\"content\":\"hello\"}")));
        assertTrue(result.contains("Created file"), result);
        assertEquals("hello", Files.readString(ws.resolve("new.txt")));
    }

    @Test
    void writeOverwriteWithoutReadRejected() throws IOException {
        Path file = Files.writeString(ws.resolve("exist.txt"), "已有内容");
        FsWriteTool tool = new FsWriteTool(policy, gate);
        String result = tool.execute(exec(json(
                "{\"path\":\"" + str(file.toString()) + "\",\"content\":\"覆盖\"}")));
        assertTrue(result.contains("read 工具"), "读前写闸门拒绝: " + result);
        assertEquals("已有内容", Files.readString(file), "文件未被改动");
    }

    @Test
    void readThenWriteGateAllows() throws IOException {
        Path file = Files.writeString(ws.resolve("gate.txt"), "旧内容");
        new FsReadTool(policy, gate).execute(exec(json("{\"path\":\"" + str(file.toString()) + "\"}")));
        String result = new FsWriteTool(policy, gate).execute(exec(json(
                "{\"path\":\"" + str(file.toString()) + "\",\"content\":\"新内容\"}")));
        assertTrue(result.contains("Updated file"), "读后写入放行: " + result);
        assertEquals("新内容", Files.readString(file));
    }

    // ---- edit ----

    @Test
    void editSingleReplacement() throws IOException {
        Path file = Files.writeString(ws.resolve("code.txt"), "public class Foo {\n}\n");
        new FsReadTool(policy, gate).execute(exec(json("{\"path\":\"" + str(file.toString()) + "\"}")));
        FsEditTool tool = new FsEditTool(policy, gate);
        String result = tool.execute(exec(json(
                "{\"path\":\"" + str(file.toString())
                + "\",\"old_string\":\"Foo\",\"new_string\":\"Bar\"}")));
        assertTrue(result.contains("Edited"), result);
        assertEquals("public class Bar {\n}\n", Files.readString(file));
    }

    @Test
    void editZeroMatchesRejected() throws IOException {
        Path file = Files.writeString(ws.resolve("a.txt"), "内容");
        new FsReadTool(policy, gate).execute(exec(json("{\"path\":\"" + str(file.toString()) + "\"}")));
        FsEditTool tool = new FsEditTool(policy, gate);
        String result = tool.execute(exec(json(
                "{\"path\":\"" + str(file.toString())
                + "\",\"old_string\":\"不存在\",\"new_string\":\"替换\"}")));
        assertTrue(result.contains("未找到"), result);
    }

    @Test
    void editMultipleMatchesRejectedWithoutReplaceAll() throws IOException {
        Path file = Files.writeString(ws.resolve("dup.txt"), "aaa\nbbb\naaa\n");
        new FsReadTool(policy, gate).execute(exec(json("{\"path\":\"" + str(file.toString()) + "\"}")));
        FsEditTool tool = new FsEditTool(policy, gate);
        String result = tool.execute(exec(json(
                "{\"path\":\"" + str(file.toString())
                + "\",\"old_string\":\"aaa\",\"new_string\":\"zzz\"}")));
        assertTrue(result.contains("2 处"), result);
    }

    @Test
    void editEmptyOldStringRejected() throws IOException {
        FsEditTool tool = new FsEditTool(policy, gate);
        String result = tool.execute(exec(json(
                "{\"path\":\"" + str(ws.resolve("a.txt").toString())
                + "\",\"old_string\":\"\",\"new_string\":\"x\"}")));
        assertTrue(result.contains("不能为空"), result);
    }

    @Test
    void editOldEqualsNewRejected() throws IOException {
        FsEditTool tool = new FsEditTool(policy, gate);
        String result = tool.execute(exec(json(
                "{\"path\":\"" + str(ws.resolve("a.txt").toString())
                + "\",\"old_string\":\"x\",\"new_string\":\"x\"}")));
        assertTrue(result.contains("相同"), result);
    }

    @Test
    void editReplaceAllReplacesEveryMatch() throws IOException {
        Path file = Files.writeString(ws.resolve("all.txt"), "aaa\nbbb\naaa\n");
        new FsReadTool(policy, gate).execute(exec(json("{\"path\":\"" + str(file.toString()) + "\"}")));
        FsEditTool tool = new FsEditTool(policy, gate);
        String result = tool.execute(exec(json(
                "{\"path\":\"" + str(file.toString())
                + "\",\"old_string\":\"aaa\",\"new_string\":\"zzz\",\"replace_all\":true}")));
        assertTrue(result.contains("2 replacements"), result);
        assertEquals("zzz\nbbb\nzzz\n", Files.readString(file));
    }

    @Test
    void editCrlfLineEndingsRestored() throws IOException {
        Path file = Files.writeString(ws.resolve("crlf.txt"), "alpha\r\nbeta\r\n");
        new FsReadTool(policy, gate).execute(exec(json("{\"path\":\"" + str(file.toString()) + "\"}")));
        FsEditTool tool = new FsEditTool(policy, gate);
        String result = tool.execute(exec(json(
                "{\"path\":\"" + str(file.toString())
                + "\",\"old_string\":\"beta\",\"new_string\":\"gamma\"}")));
        assertTrue(result.contains("Edited"), result);
        assertEquals("alpha\r\ngamma\r\n", Files.readString(file), "CRLF 行尾写回恢复");
    }

    @Test
    void editUnreadFileRejected() throws IOException {
        Path file = Files.writeString(ws.resolve("gated.txt"), "原文内容");
        FsEditTool tool = new FsEditTool(policy, gate);
        String result = tool.execute(exec(json(
                "{\"path\":\"" + str(file.toString())
                + "\",\"old_string\":\"原文\",\"new_string\":\"新文\"}")));
        assertTrue(result.contains("读前写闸门"), "edit 未读拒绝: " + result);
        assertEquals("原文内容", Files.readString(file), "文件未被改动");
    }

    @Test
    void editAfterReadAllows() throws IOException {
        Path file = Files.writeString(ws.resolve("readable.txt"), "原文内容");
        new FsReadTool(policy, gate).execute(exec(json("{\"path\":\"" + str(file.toString()) + "\"}")));
        String result = new FsEditTool(policy, gate).execute(exec(json(
                "{\"path\":\"" + str(file.toString())
                + "\",\"old_string\":\"原文\",\"new_string\":\"新文\"}")));
        assertTrue(result.contains("Edited"), "read 后 edit 放行: " + result);
        assertEquals("新文内容", Files.readString(file));
    }

    // ---- glob ----

    @Test
    void globFindsMatchingFiles() throws IOException {
        Files.writeString(ws.resolve("A.java"), "class A");
        Files.writeString(ws.resolve("B.java"), "class B");
        Files.writeString(ws.resolve("C.txt"), "text");
        FsGlobTool tool = new FsGlobTool(policy);
        String result = tool.execute(exec(json("{\"pattern\":\"**/*.java\"}")));
        assertTrue(result.contains("A.java") && result.contains("B.java"), result);
        assertFalse(result.contains("C.txt"), "非匹配文件不出现");
    }

    @Test
    void globSkipsVcsDirs() throws IOException {
        Files.createDirectories(ws.resolve(".git"));
        Files.writeString(ws.resolve(".git").resolve("hidden.java"), "class H");
        Files.writeString(ws.resolve("visible.java"), "class V");
        FsGlobTool tool = new FsGlobTool(policy);
        String result = tool.execute(exec(json("{\"pattern\":\"**/*.java\"}")));
        assertTrue(result.contains("visible.java"), result);
        assertFalse(result.contains("hidden.java"), "VCS 元数据目录跳过");
    }

    @Test
    void globSortsByMtimeDesc() throws IOException {
        Path oldFile = Files.writeString(ws.resolve("old.txt"), "o");
        Path newFile = Files.writeString(ws.resolve("new.txt"), "n");
        Files.setLastModifiedTime(oldFile,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        Files.setLastModifiedTime(newFile,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        FsGlobTool tool = new FsGlobTool(policy);
        String result = tool.execute(exec(json("{\"pattern\":\"**/*.txt\"}")));
        assertTrue(result.indexOf("new.txt") < result.indexOf("old.txt"), "修改时间倒序: " + result);
    }

    @Test
    void globTruncatesAt100WithMoreCount() throws IOException {
        for (int i = 1; i <= 105; i++)
            Files.writeString(ws.resolve(String.format("m%03d.txt", i)), "x");
        FsGlobTool tool = new FsGlobTool(policy);
        String result = tool.execute(exec(json("{\"pattern\":\"**/*.txt\"}")));
        long lines = result.lines().filter(s -> !s.isBlank() && !s.startsWith("…")).count();
        assertEquals(100, lines, "截断 100 条");
        assertTrue(result.contains("and 5 more files"), "未显示计数: " + result);
    }

    @Test
    void globAnchoredRelativePatternMatches() throws IOException {
        // 锚定相对模式（验收实测发现）：pattern 相对搜索根匹配——
        // 绝对路径直接对 matcher.matches 会永不命中（仅 ** 前缀形态侥幸可用）
        Files.createDirectories(ws.resolve("docs").resolve("adr"));
        Files.writeString(ws.resolve("docs").resolve("adr").resolve("0001-中文文件名.md"), "x");
        Files.writeString(ws.resolve("docs").resolve("top.md"), "x");
        FsGlobTool tool = new FsGlobTool(policy);
        String result = tool.execute(exec(json("{\"pattern\":\"docs/adr/*.md\"}")));
        assertTrue(result.contains("0001-中文文件名.md"),
                "锚定相对模式命中（含中文文件名）: " + result);
        assertFalse(result.contains("top.md"), "目录外不命中");
    }

    @Test
    void globPatternRelativizesToProvidedPath() throws IOException {
        // path 参数 = 搜索起始目录：pattern 相对该目录解析（不是相对 workspace 根）
        Files.createDirectories(ws.resolve("src").resolve("main"));
        Files.writeString(ws.resolve("src").resolve("main").resolve("App.java"), "class App");
        FsGlobTool tool = new FsGlobTool(policy);
        String result = tool.execute(exec(json(
                "{\"pattern\":\"main/*.java\",\"path\":\"src\"}")));
        assertTrue(result.contains("App.java"), "pattern 相对 path 参数解析: " + result);
    }

    @Test
    void gitignoreIgnoredTargetsVanishFromGlobAndGrep() throws IOException {
        // 同口径集成断言（M23 工单 08）：.gitignore 忽略的目标在 glob 结果与
        // grep 命中里同时消失——判定器为三消费点唯一口径
        Files.writeString(ws.resolve(".gitignore"), "secrets.txt\n");
        Files.writeString(ws.resolve("secrets.txt"), "token=abc\n");
        Files.writeString(ws.resolve("visible.txt"), "token=ok\n");
        FsGlobTool glob = new FsGlobTool(policy);
        String globResult = glob.execute(exec(json("{\"pattern\":\"**/*.txt\"}")));
        assertTrue(globResult.contains("visible.txt"), "可见文件在 glob: " + globResult);
        assertFalse(globResult.contains("secrets.txt"), ".gitignore 目标从 glob 消失");
        FsGrepTool grep = new FsGrepTool(policy);
        String grepResult = grep.execute(exec(json("{\"pattern\":\"token\"}")));
        assertTrue(grepResult.contains("visible.txt"), "可见文件在 grep: " + grepResult);
        assertFalse(grepResult.contains("secrets.txt"), ".gitignore 目标从 grep 消失");
    }

    // ---- grep ----

    @Test
    void grepFindsMatchingLines() throws IOException {
        Files.writeString(ws.resolve("code.txt"), "public void foo() {\n}\n");
        FsGrepTool tool = new FsGrepTool(policy);
        String result = tool.execute(exec(json(
                "{\"pattern\":\"foo\",\"path\":\"" + str(ws.resolve("code.txt").toString()) + "\"}")));
        assertTrue(result.contains("foo()"), result);
        assertTrue(result.contains("1:"), result);
    }

    @Test
    void grepIncludeFiltersByGlob() throws IOException {
        Files.writeString(ws.resolve("a.java"), "needle\n");
        Files.writeString(ws.resolve("b.txt"), "needle\n");
        FsGrepTool tool = new FsGrepTool(policy);
        String result = tool.execute(exec(json("{\"pattern\":\"needle\"}")));
        assertTrue(result.contains("a.java") && result.contains("b.txt"), "无 include 时全搜: " + result);
        String filtered = tool.execute(exec(json("{\"pattern\":\"needle\",\"include\":\"*.java\"}")));
        assertTrue(filtered.contains("a.java"), filtered);
        assertFalse(filtered.contains("b.txt"), "include 过滤生效");
    }

    @Test
    void grepIncludeRejectsNegationAndList() {
        FsGrepTool tool = new FsGrepTool(policy);
        String negation = tool.execute(exec(json(
                "{\"pattern\":\"x\",\"include\":\"!*.java\"}")));
        assertTrue(negation.contains("不支持取反"), negation);
        String list = tool.execute(exec(json(
                "{\"pattern\":\"x\",\"include\":\"*.java,*.txt\"}")));
        assertTrue(list.contains("单个 glob"), list);
    }

    @Test
    void grepTruncatesAt250WithMoreCount() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 300; i++) sb.append("hit-").append(i).append('\n');
        Path file = Files.writeString(ws.resolve("many.txt"), sb.toString());
        FsGrepTool tool = new FsGrepTool(policy);
        String result = tool.execute(exec(json(
                "{\"pattern\":\"hit\",\"path\":\"" + str(file.toString()) + "\"}")));
        assertTrue(result.contains("Found 300 matches"), result);
        assertTrue(result.contains("and 50 more matches"), "截断 250 条带余量计数");
    }

    @Test
    void grepSkipsBinaryLines() throws IOException {
        Path file = ws.resolve("mix.bin");
        Files.write(file, ("needle one\nneedle\u0000binary\nneedle three\n")
                .getBytes(StandardCharsets.UTF_8));
        FsGrepTool tool = new FsGrepTool(policy);
        String result = tool.execute(exec(json(
                "{\"pattern\":\"needle\",\"path\":\"" + str(file.toString()) + "\"}")));
        assertTrue(result.contains("1: needle one"), result);
        assertTrue(result.contains("3: needle three"), "NUL 行之后仍继续匹配: " + result);
        assertFalse(result.contains("2:"), "含 NUL 的二进制行跳过");
    }
}
