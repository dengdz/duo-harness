package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.FileSystems;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

/** glob 工具：PathMatcher 文件模式匹配 + VCS 目录跳过 + 修改时间倒序 + 截断 100 条。 */
public final class FsGlobTool implements ToolDefinition {

    public static final String NAME = "glob";
    private static final int MAX_RESULTS = 100;
    private static final Set<String> VCS_DIRS = Set.of(".git", ".svn", ".hg", ".bzr", ".jj");

    private final WorkspacePolicy workspace;

    public FsGlobTool(WorkspacePolicy workspace) { this.workspace = workspace; }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "按 glob 模式搜索文件（如 **/*.java）。返回按修改时间倒序排列的文件列表。跳过 .git 等版本控制目录。";
    }
    @Override public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"type\":\"object\",\"properties\":{"
                + "\"pattern\":{\"type\":\"string\",\"description\":\"glob 模式（如 **/*.java）\"},"
                + "\"path\":{\"type\":\"string\",\"description\":\"搜索起始目录（默认 workspace 根）\"}"
                + "},\"required\":[\"pattern\"]}");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** 纯只读、无共享可变状态（ADR-0018 首批标注）。 */
    @Override public boolean isConcurrencySafe(JsonNode args) { return true; }

    @Override public String execute(ToolExecution exec) {
        JsonNode args = exec.args();
        String pattern = args.path("pattern").asText("");
        if (pattern.isBlank()) return error("参数 pattern 不能为空");
        String basePath = args.path("path").asText("");
        Path searchRoot = basePath.isBlank()
                ? workspace.root() : workspace.resolveInWorkspaceOrNull(basePath);
        if (searchRoot == null || !Files.isDirectory(searchRoot))
            return error("搜索目录不存在: " + (basePath.isBlank() ? "workspace 根" : basePath));

        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (IllegalArgumentException e) {
            return error("glob 模式无效: " + e.getMessage());
        }
        try (Stream<Path> stream = Files.walk(searchRoot)) {
            var results = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isInVcsDir(p))
                    .filter(p -> matcher.matches(p))
                    .sorted(Comparator.comparing(
                            (Path p) -> p.toFile().lastModified()).reversed())
                    .toList();
            if (results.isEmpty()) return "No files found";
            int shown = Math.min(results.size(), MAX_RESULTS);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < shown; i++) sb.append(results.get(i)).append('\n');
            if (results.size() > shown)
                sb.append("… and ").append(results.size() - shown).append(" more files\n");
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("glob 无法遍历 " + searchRoot + ": " + e.getMessage(), e);
        }
    }

    /** 路径是否在 VCS 元数据目录内（.git/.svn 等——检索结果不含版本控制内部文件）。 */
    private static boolean isInVcsDir(Path path) {
        for (Path part : path) {
            if (VCS_DIRS.contains(part.toString())) return true;
        }
        return false;
    }

    private static String error(String msg) { return "[glob 错误] " + msg; }
}
