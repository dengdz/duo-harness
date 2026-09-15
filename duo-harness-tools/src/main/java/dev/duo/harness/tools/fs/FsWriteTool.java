package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** write 工具：原子替换（临时文件 + ATOMIC_MOVE）+ 读前写闸门。 */
public final class FsWriteTool implements ToolDefinition {

    public static final String NAME = "write";

    private final WorkspacePolicy workspace;
    private final ReadGate readGate;

    public FsWriteTool(WorkspacePolicy workspace, ReadGate readGate) {
        this.workspace = workspace;
        this.readGate = readGate;
    }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "写入文件内容（覆盖已有或新建）。覆盖已有文件前须先用 read 读取（读前写闸门）。";
    }
    @Override public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"文件路径\"},"
                + "\"content\":{\"type\":\"string\",\"description\":\"写入内容（空串=清空文件）\"}"
                + "},\"required\":[\"path\",\"content\"]}");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Override public boolean requiresApproval() { return true; }
    @Override public String execute(ToolExecution exec) {
        JsonNode args = exec.args();
        String raw = args.path("path").asText("");
        String content = args.path("content").asText("");
        if (raw.isBlank()) return error("参数 path 不能为空");
        Path target = workspace.resolveInWorkspaceOrNull(raw);
        if (target == null) return error("无效路径: " + raw);
        boolean exists = Files.exists(target);
        if (exists && !readGate.hasRead(target)) {
            return error("文件已存在但未被读取——请先用 read 工具读取 " + raw + "，再执行写入（读前写闸门）");
        }
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".fs_write_", ".tmp");
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException("write 无法写入文件 " + raw + ": " + e.getMessage(), e);
        }
        return (exists ? "Updated file " : "Created file ") + target;
    }

    private static String error(String msg) { return "[write 错误] " + msg; }
}
