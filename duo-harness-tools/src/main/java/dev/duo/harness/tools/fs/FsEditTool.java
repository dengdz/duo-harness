package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** edit 工具：精确字符串替换（LF 归一匹配域 + CRLF 写回恢复 + 四态结构化失败 + replace_all）。 */
public final class FsEditTool implements ToolDefinition {

    public static final String NAME = "edit";

    private final WorkspacePolicy workspace;
    private final ReadGate readGate;

    public FsEditTool(WorkspacePolicy workspace, ReadGate readGate) {
        this.workspace = workspace;
        this.readGate = readGate;
    }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "精确替换文件中的字符串。old_string 必须在文件中唯一匹配（或设 replace_all 全部替换）。"
                + "使用前须先用 read 读取文件。";
    }
    @Override public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"文件路径\"},"
                + "\"old_string\":{\"type\":\"string\",\"description\":\"要替换的精确文本\"},"
                + "\"new_string\":{\"type\":\"string\",\"description\":\"替换后的文本\"},"
                + "\"replace_all\":{\"type\":\"boolean\",\"description\":\"替换所有匹配（默认 false）\"}"
                + "},\"required\":[\"path\",\"old_string\",\"new_string\"]}");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Override public boolean requiresApproval() { return true; }
    @Override public String execute(ToolExecution exec) {
        JsonNode args = exec.args();
        String raw = args.path("path").asText("");
        String oldStr = args.path("old_string").asText("");
        String newStr = args.path("new_string").asText("");
        boolean replaceAll = args.path("replace_all").asBoolean(false);
        if (raw.isBlank()) return error("参数 path 不能为空");
        if (oldStr.isEmpty()) return error("old_string 不能为空");
        if (oldStr.equals(newStr)) return error("old_string 与 new_string 相同——无需编辑");
        Path target = workspace.resolveInWorkspaceOrNull(raw);
        if (target == null || !Files.isRegularFile(target))
            return error("文件不存在: " + raw);
        if (!readGate.hasRead(target)) {
            return error("文件未被读取——请先用 read 工具读取 " + raw + "，再执行编辑（读前写闸门）");
        }

        String content;
        try { content = Files.readString(target, StandardCharsets.UTF_8); }
        catch (IOException e) {
            throw new RuntimeException("edit 无法读取文件 " + raw + ": " + e.getMessage(), e);
        }

        String normalized = content.replace("\r\n", "\n");
        String normalizedOld = oldStr.replace("\r\n", "\n");
        String normalizedNew = newStr.replace("\r\n", "\n");
        int count = countOccurrences(normalized, normalizedOld);
        if (count == 0) return error("old_string 未找到于 " + raw);
        if (count > 1 && !replaceAll)
            return error("old_string 匹配 " + count + " 处——请提供更多上下文缩小范围，或设 replace_all=true 替换全部");

        String result = replaceAll
                ? normalized.replace(normalizedOld, normalizedNew)
                : replaceFirst(normalized, normalizedOld, normalizedNew);
        if (content.contains("\r\n")) result = result.replace("\n", "\r\n");
        try {
            Files.writeString(target, result, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("edit 无法写回文件 " + raw + ": " + e.getMessage(), e);
        }
        return "Edited " + raw + " (" + (replaceAll ? count : 1) + " replacement"
                + (replaceAll && count > 1 ? "s" : "") + ")";
    }

    private static int countOccurrences(String text, String pattern) {
        if (pattern.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) { count++; idx += pattern.length(); }
        return count;
    }

    private static String replaceFirst(String text, String from, String to) {
        int idx = text.indexOf(from);
        return idx == -1 ? text : text.substring(0, idx) + to + text.substring(idx + from.length());
    }

    private static String error(String msg) { return "[edit 错误] " + msg; }
}
