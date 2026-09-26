package dev.duo.harness.agent.deliverable;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.session.SessionExport.ChangeReport;
import dev.duo.harness.session.SessionExport.ChangeRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 变更摘要供给（M26 工单 05，ADR-0028 grill 决策七：git 对账为主 + 工具记录兜底，
 * 会话级）：导出报告「变更摘要」章节的数据源。
 *
 * <p><b>git 对账</b>：会话首尾各拍一次快照比对——首拍经 {@link #markStart} 在呈现位
 * 会话绑定点接线（仅空会话拍摄：resume 会话不拍，导出退化为工具记录形态）；拍法 =
 * {@code git stash create}（无副作用捕获 tracked 工作区状态，空输出即干净工作区
 * 记 HEAD）+ {@code git ls-files --others}（未跟踪清单——新增文件据此识别）；导出
 * 时重拍终态，{@code git diff --numstat} 出行数，新增未跟踪 = 差集（行数 Java 计）。
 * 非 git 目录 / git 失败 → {@code gitAvailable=false}，导出退化为工具记录。</p>
 *
 * <p><b>工具记录兜底</b>：write/edit 工具调用的 path 参数聚合（永远可用，无行数
 * 统计）——bash 等旁路写不入此清单（口径记档）。快照表按会话 id 常驻内存（个人
 * 规模无界增长可忽略），进程重启即失——resume 会话由此自然退化。</p>
 */
public final class ChangeSummary {

    private static final Logger log = LoggerFactory.getLogger(ChangeSummary.class);

    /** 文件写工具名（工具记录兜底的口径集合）。 */
    private static final Set<String> FILE_WRITE_TOOLS = Set.of("write", "edit");
    /** 二进制嗅探字节数（前 8KB 含 NUL 判二进制）。 */
    private static final int BINARY_SNIFF_BYTES = 8000;
    /** git 进程超时秒数。 */
    private static final int GIT_TIMEOUT_SECONDS = 10;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final Map<String, Base> BASES = new ConcurrentHashMap<>();

    /** 会话开始时的 git 状态（gitAvailable=false = 非 git 目录/捕获失败）。 */
    private record Base(boolean gitAvailable, Path cwd, String ref, Set<String> untracked) {
    }

    private ChangeSummary() {
    }

    /**
     * 会话开始快照（呈现位绑定点接线）：仅空会话拍摄——resume 会话的"开始"在
     * 历史进程里，本进程状态不能代表，导出自然退化为工具记录形态。
     */
    public static void markStart(Session session) {
        Objects.requireNonNull(session, "session");
        if (!session.events().isEmpty()) {
            return; // resume/续接：不拍（语义见类 doc）
        }
        Path cwd = session.cwd();
        if (cwd == null) {
            BASES.put(session.id(), new Base(false, null, null, Set.of()));
            return;
        }
        BASES.put(session.id(), capture(cwd));
    }

    /** 导出时刻的变更报告（重复导出不重拍首帧、无副作用；两导出间文件可变则结果随实况）。 */
    public static ChangeReport report(Session session) {
        List<String> toolPaths = toolPaths(session);
        Base base = BASES.get(session.id());
        if (base == null || !base.gitAvailable() || base.cwd() == null) {
            return new ChangeReport(false, List.of(), toolPaths);
        }
        Base end = capture(base.cwd());
        if (!end.gitAvailable()) {
            return new ChangeReport(false, List.of(), toolPaths);
        }
        return new ChangeReport(true, diffRows(base.cwd(), base, end), toolPaths);
    }

    // ---- 快照与对账 ----

    private static Base capture(Path cwd) {
        try {
            String ref = git(cwd, "stash", "create").strip();
            if (ref.isEmpty()) {
                ref = git(cwd, "rev-parse", "HEAD").strip(); // 干净工作区 = HEAD 状态
            }
            if (ref.isEmpty()) {
                // 理论不可达：空仓库时 rev-parse 非零退出走异常路径——防御保留
                return new Base(false, cwd, null, Set.of());
            }
            return new Base(true, cwd, ref,
                    Set.copyOf(gitLines(cwd, "ls-files", "--others", "--exclude-standard")));
        } catch (Exception e) {
            log.debug("git 快照不可用（非 git 目录或 git 失败）: {}", cwd, e);
            return new Base(false, cwd, null, Set.of());
        }
    }

    private static List<ChangeRow> diffRows(Path cwd, Base base, Base end) {
        List<ChangeRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            for (String line : gitLines(cwd, "diff", "--numstat", "-M", base.ref(), end.ref())) {
                ChangeRow row = parseNumstat(line);
                if (row != null) {
                    rows.add(row);
                    seen.add(row.path());
                }
            }
        } catch (Exception e) {
            log.warn("git 对账失败（退化为无行数不可用）: {}", cwd, e);
            return List.of();
        }
        // 新增未跟踪文件 = 终态未跟踪 − 首拍未跟踪（行数 Java 计——stash 不含 untracked）
        Set<String> newUntracked = new LinkedHashSet<>(end.untracked());
        newUntracked.removeAll(base.untracked());
        for (String rel : newUntracked) {
            if (seen.add(rel)) {
                Path file = cwd.resolve(rel);
                rows.add(new ChangeRow(rel, countLines(file), "0"));
            }
        }
        return rows;
    }

    private static ChangeRow parseNumstat(String line) {
        String[] parts = line.split("\t", 3);
        if (parts.length < 3) {
            return null;
        }
        // 二进制行 "-\t-\t..." 映射 null（ChangeRow 契约：null → 渲染 (binary)）；
        // 重命名形态 "old => new" 原样呈现（括号记法保留 git 输出）
        return new ChangeRow(parts[2],
                "-".equals(parts[0]) ? null : parts[0],
                "-".equals(parts[1]) ? null : parts[1]);
    }

    /** 行数流式计数（不整文件驻内存）；读取失败返回 "?"（渲染原样——可观测的降级标记）。 */
    private static String countLines(Path file) {
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int lines = 0;
            long total = 0;
            boolean binary = false;
            int read;
            while ((read = in.read(buf)) != -1) {
                if (!binary) {
                    for (int i = 0; i < read; i++) {
                        if (buf[i] == 0) {
                            binary = true;
                            break;
                        }
                    }
                    if (!binary && total < BINARY_SNIFF_BYTES) {
                        // 首窗内无 NUL 即按文本计（嗅探窗 = 前 BINARY_SNIFF_BYTES 字节）
                    }
                }
                if (!binary) {
                    for (int i = 0; i < read; i++) {
                        if (buf[i] == '\n') {
                            lines++;
                        }
                    }
                }
                total += read;
            }
            if (binary) {
                return null; // 二进制：行数无意义（渲染为 (binary)）
            }
            return String.valueOf(lines);
        } catch (IOException e) {
            return "?";
        }
    }

    // ---- 工具记录兜底 ----

    /** write/edit 工具触碰的路径聚合（去重保序；bash 旁路写不在口径内——记档）。 */
    private static List<String> toolPaths(Session session) {
        Set<String> paths = new LinkedHashSet<>();
        for (var event : session.events()) {
            if (!SessionEvent.TOOL_CALL.equals(event.type())) {
                continue;
            }
            String toolName = event.toolName();
            if (toolName == null || !FILE_WRITE_TOOLS.contains(toolName)) {
                continue;
            }
            try {
                JsonNode args = JSON.readTree(event.text());
                String path = args.path("path").asText("").strip();
                if (!path.isEmpty()) {
                    paths.add(path);
                }
            } catch (Exception ignored) {
                // 坏参数形态跳过（聚合是尽力而为的兜底清单）
            }
        }
        return List.copyOf(paths);
    }

    // ---- git 进程 ----

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(dir.toString());
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        // stderr 丢弃：不消费的管道写满 64KB 会令 git 阻塞在 stderr、stdout 永不 EOF
        // （waitFor 超时防线永不触达的潜在死锁）
        pb.redirectError(java.lang.ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();
        String stdout;
        try (var in = process.getInputStream()) {
            stdout = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("git 超时: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IOException("git 失败 (" + process.exitValue() + "): "
                    + String.join(" ", command));
        }
        return stdout;
    }

    private static List<String> gitLines(Path dir, String... args)
            throws IOException, InterruptedException {
        return git(dir, args).lines().map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
    }
}
