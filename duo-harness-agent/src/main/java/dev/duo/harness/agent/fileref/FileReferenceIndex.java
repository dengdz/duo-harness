package dev.duo.harness.agent.fileref;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 工作区路径索引（M21 工单 07）：懒遍历，路径 only——不读文件内容。 */
final class FileReferenceIndex {

    private final Path workspaceRoot;
    private final int maxEntries;
    private final dev.duo.harness.tools.fs.IgnorePolicy ignore;

    FileReferenceIndex(Path workspaceRoot, int maxEntries,
                       dev.duo.harness.tools.fs.IgnorePolicy ignore) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.maxEntries = maxEntries;
        this.ignore = ignore;
    }

    record Candidate(String path, boolean directory) { }

    List<Candidate> buildIndex() {
        List<Candidate> out = new ArrayList<>();
        collect(workspaceRoot, "", out, 0);
        return out;
    }

    private void collect(Path dir, String prefix, List<Candidate> out, int depth) {
        if (out.size() >= maxEntries || depth > 15) return;
        try (var entries = Files.list(dir)) {
            for (var entry : entries.sorted().toList()) {
                if (out.size() >= maxEntries) return;
                String name = entry.getFileName().toString();
                // 目录 symlink 只列候选不下钻（ADR-0022 决策 7：目录 symlink 不跟随——
                // 防止索引逃出 workspace 或绕进排除目录的符号链接别名）
                boolean isDir = Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                boolean isSymlink = Files.isSymbolicLink(entry);
                // 忽略判定（M23 工单 08）：与 glob/grep 同一判定器同一口径——
                // 补全看得到的 grep 一定看得到
                if (ignore.ignored(entry, isDir)) continue;
                String rel = prefix.isEmpty() ? name : prefix + "/" + name;
                out.add(new Candidate(rel, isDir));
                if (isDir && !isSymlink) collect(entry, rel, out, depth + 1);
            }
        } catch (IOException ignored) {
            // 子树不可读贡献 0 候选：防护矩阵语义，不炸穿整表构建
        }
    }

    Path resolve(String relativePath) {
        Path target = workspaceRoot.resolve(relativePath).normalize();
        return target.startsWith(workspaceRoot) ? target : null;
    }
}
