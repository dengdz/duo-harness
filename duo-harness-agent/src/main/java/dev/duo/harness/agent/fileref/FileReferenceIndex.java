package dev.duo.harness.agent.fileref;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 工作区路径索引（M21 工单 07）：懒遍历，路径 only——不读文件内容。 */
final class FileReferenceIndex {

    private static final Set<String> EXCLUDED = Set.of(
            ".git", ".svn", ".hg", ".jj", "node_modules", "dist", "build", "out",
            "coverage", "target", ".next", ".nuxt", ".turbo", ".venv",
            "__pycache__", ".pytest_cache", ".mypy_cache", ".gradle", ".idea");

    private final Path workspaceRoot;
    private final int maxEntries;

    FileReferenceIndex(Path workspaceRoot, int maxEntries) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.maxEntries = maxEntries;
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
                if (EXCLUDED.contains(name)) continue;
                String rel = prefix.isEmpty() ? name : prefix + "/" + name;
                boolean isDir = Files.isDirectory(entry);
                out.add(new Candidate(rel, isDir));
                if (isDir) collect(entry, rel, out, depth + 1);
            }
        } catch (IOException ignored) { }
    }

    Path resolve(String relativePath) {
        Path target = workspaceRoot.resolve(relativePath).normalize();
        return target.startsWith(workspaceRoot) ? target : null;
    }
}
