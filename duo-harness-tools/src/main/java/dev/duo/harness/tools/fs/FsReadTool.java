package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** read 工具：三帽窗口（行数/单行字符/总字节）+ 精确总行数 + 续读 footer + 二进制拒读。读取成功即登记读前写闸门。 */
public final class FsReadTool implements ToolDefinition {

    public static final String NAME = "read";
    private static final int MAX_LINES = 2000;
    private static final int MAX_LINE_CHARS = 2000;
    private static final int MAX_TOTAL_BYTES = 51200;

    private final WorkspacePolicy workspace;
    private final ReadGate readGate;

    public FsReadTool(WorkspacePolicy workspace, ReadGate readGate) {
        this.workspace = workspace;
        this.readGate = readGate;
    }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "读取文件内容（带行号）。大文件用 offset/limit 分段读取。不适用于二进制文件。";
    }
    @Override public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"文件路径\"},"
                + "\"offset\":{\"type\":\"number\",\"description\":\"起始行号(1起始,默认1)\"},"
                + "\"limit\":{\"type\":\"number\",\"description\":\"最大行数(默认2000)\"}"
                + "},\"required\":[\"path\"]}");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** 纯只读且读前写闸门登记为并发安全集（ADR-0018 首批标注）。 */
    @Override public boolean isConcurrencySafe(JsonNode args) { return true; }

    @Override public String execute(ToolExecution exec) {
        JsonNode args = exec.args();
        String raw = args.path("path").asText("");
        if (raw.isBlank()) return error("参数 path 不能为空");
        Path target = workspace.resolveInWorkspaceOrNull(raw);
        if (target == null || !Files.isRegularFile(target))
            return error("文件不存在: " + raw);
        byte[] bytes;
        try { bytes = Files.readAllBytes(target); }
        catch (IOException e) {
            throw new RuntimeException("read 无法读取文件 " + raw + ": " + e.getMessage(), e);
        }
        for (int i = 0; i < Math.min(bytes.length, 8192); i++) {
            if (bytes[i] == 0) return error("二进制文件，read 工具不支持");
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\n");
        int total = lines.length;
        int off = clamp(args, "offset", 1);
        int lim = clamp(args, "limit", MAX_LINES);
        if (off > total) return error("offset " + off + " 超出总行数 " + total);
        readGate.markRead(target);
        StringBuilder sb = new StringBuilder("<path>").append(target)
                .append("</path>\n<type>file</type>\n<content>\n");
        int end = Math.min(off - 1 + lim, total);
        for (int i = off - 1; i < end; i++) {
            String ln = lines[i].length() > MAX_LINE_CHARS
                    ? lines[i].substring(0, MAX_LINE_CHARS) + "…" : lines[i];
            sb.append(i + 1).append(": ").append(ln).append('\n');
            if (sb.length() > MAX_TOTAL_BYTES) {
                sb.append("(Output capped. Showing lines ").append(off).append('-').append(i)
                  .append(". Use offset=").append(i + 1).append(" to continue.)\n");
                return sb.toString();
            }
        }
        sb.append(end < total
                ? "(Showing lines " + off + "-" + end + " of " + total + ". Use offset=" + (end + 1) + " to continue.)"
                : "(End of file - total " + total + " lines)");
        sb.append('\n');
        return sb.toString();
    }

    private static int clamp(JsonNode args, String field, int def) {
        JsonNode n = args.path(field);
        return n.isNumber() && n.asInt() >= 1 ? n.asInt() : def;
    }
    private static String error(String msg) { return "[read 错误] " + msg; }
}
