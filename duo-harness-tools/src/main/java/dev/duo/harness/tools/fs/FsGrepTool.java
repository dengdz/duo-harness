package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Stream;

/** grep 工具：逐文件逐行正则匹配 + include 单 glob 过滤（拒取反与逗号列表）+ NUL 二进制行跳过 + VCS 目录跳过 + 截断 250 条。 */
public final class FsGrepTool implements ToolDefinition {

    public static final String NAME = "grep";
    private static final int MAX_RESULTS = 250;
    private static final Set<String> VCS_DIRS = Set.of(".git", ".svn", ".hg", ".bzr", ".jj");

    private final WorkspacePolicy workspace;

    public FsGrepTool(WorkspacePolicy workspace) { this.workspace = workspace; }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "按正则表达式搜索文件内容，命中输出为 路径:行号:文本。跳过含 NUL 的二进制行与 .git 等版本控制目录。";
    }
    @Override public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"type\":\"object\",\"properties\":{"
                + "\"pattern\":{\"type\":\"string\",\"description\":\"正则表达式\"},"
                + "\"path\":{\"type\":\"string\",\"description\":\"搜索起始目录（默认 workspace 根）\"},"
                + "\"include\":{\"type\":\"string\",\"description\":\"文件名 glob 过滤（如 *.java）\"}"
                + "},\"required\":[\"pattern\"]}");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** 纯只读、无共享可变状态（ADR-0018 首批标注）。 */
    @Override public boolean isConcurrencySafe(JsonNode args) { return true; }

    @Override public String execute(ToolExecution exec) {
        JsonNode args = exec.args();
        String regex = args.path("pattern").asText("");
        if (regex.isBlank()) return error("参数 pattern 不能为空");
        Pattern compiled;
        try { compiled = Pattern.compile(regex); }
        catch (Exception e) { return error("正则表达式无效: " + e.getMessage()); }
        String includeGlob = args.path("include").asText("");
        if (includeGlob.contains("!"))
            return error("include 不支持取反（" + includeGlob + "）——请改用正向 glob，或缩小 path 搜索范围后多次调用");
        if (includeGlob.contains(","))
            return error("include 仅支持单个 glob（" + includeGlob + "）——请拆分为多次调用，每次一个 glob");
        PathMatcher includeMatcher = includeGlob.isBlank()
                ? null : FileSystems.getDefault().getPathMatcher("glob:" + includeGlob);
        String basePath = args.path("path").asText("");
        Path searchRoot = basePath.isBlank()
                ? workspace.root() : workspace.resolveInWorkspaceOrNull(basePath);
        if (searchRoot == null) return error("搜索路径不存在");
        // 单文件与目录均可搜索
        List<String> hits = new ArrayList<>();
        if (Files.isRegularFile(searchRoot)) {
            searchFile(searchRoot, compiled, hits);
        } else if (Files.isDirectory(searchRoot)) {
            try (Stream<Path> stream = Files.walk(searchRoot)) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> !isInVcsDir(p))
                      .filter(p -> includeMatcher == null || includeMatcher.matches(p.getFileName()))
                      .forEach(p -> searchFile(p, compiled, hits));
            } catch (IOException e) {
                throw new RuntimeException("grep 无法遍历 " + searchRoot + ": " + e.getMessage(), e);
            }
        }
        if (hits.isEmpty()) return "No matches found";
        StringBuilder sb = new StringBuilder("Found ").append(hits.size()).append(" matches:\n");
        int shown = Math.min(hits.size(), MAX_RESULTS);
        for (int i = 0; i < shown; i++) sb.append(hits.get(i)).append('\n');
        if (hits.size() > shown) sb.append("… and ").append(hits.size() - shown).append(" more matches\n");
        return sb.toString();
    }

    /** 单文件逐行正则匹配（IO 错误跳过该文件，不中断整体搜索）。 */
    private static void searchFile(Path file, Pattern compiled, List<String> hits) {
        byte[] bytes;
        try { bytes = Files.readAllBytes(file); }
        catch (IOException ignored) { return; }
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].endsWith("\r")
                    ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            if (line.indexOf('\0') != -1) continue;
            if (compiled.matcher(line).find()) {
                hits.add(file + ":" + (i + 1) + ": " + line);
            }
        }
    }

    private static boolean isInVcsDir(Path path) {
        for (Path part : path) {
            if (VCS_DIRS.contains(part.toString())) return true;
        }
        return false;
    }

    private static String error(String msg) { return "[grep 错误] " + msg; }
}
