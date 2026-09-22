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
import java.util.stream.Stream;

/** glob 工具：PathMatcher 文件模式匹配 + 忽略判定跳过（M23 工单 08）+ 修改时间倒序 + 截断 100 条。 */
public final class FsGlobTool implements ToolDefinition {

    public static final String NAME = "glob";
    private static final int MAX_RESULTS = 100;

    private final WorkspacePolicy workspace;
    private final IgnorePolicy ignore;

    public FsGlobTool(WorkspacePolicy workspace) { this(workspace, IgnorePolicy.load(workspace.root())); }

    public FsGlobTool(WorkspacePolicy workspace, IgnorePolicy ignore) {
        this.workspace = workspace;
        this.ignore = ignore;
    }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "按 glob 模式搜索文件（如 **/*.java）。返回按修改时间倒序排列的文件列表。跳过 .gitignore 忽略的目标与版本控制/产物目录。";
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

        java.util.List<PathMatcher> matchers;
        try {
            matchers = compileMatchers(pattern);
        } catch (IllegalArgumentException e) {
            return error("glob 模式无效: " + e.getMessage());
        }
        try (Stream<Path> stream = Files.walk(searchRoot)) {
            // pattern 按相对搜索根解析（锚定模式如 docs/adr/*.md 相对路径匹配；
            // ** 前缀形态两种口径下均命中）——walk 产出绝对路径，须先相对化
            final Path root = searchRoot;
            var results = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !ignore.ignored(p, false))
                    .filter(p -> {
                        Path rel = root.relativize(p);
                        for (PathMatcher m : matchers) {
                            if (m.matches(rel)) {
                                return true;
                            }
                        }
                        return false;
                    })
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

    /**
     * 模式编译为匹配器组：原样 + 双星前缀的零段变体（Java glob 的双星模式
     * 匹配不了零目录段的直接文件，去前缀变体补上——如任意深度 .java 匹配
     * 根下直接的 A.java）。
     */
    private static java.util.List<PathMatcher> compileMatchers(String pattern) {
        java.util.List<PathMatcher> matchers = new java.util.ArrayList<>();
        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
        if (pattern.startsWith("**/")) {
            matchers.add(FileSystems.getDefault()
                    .getPathMatcher("glob:" + pattern.substring(3)));
        }
        return matchers;
    }


    private static String error(String msg) { return "[glob 错误] " + msg; }
}
