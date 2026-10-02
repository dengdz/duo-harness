package dev.duo.harness.session;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话导出渲染（M21 工单 09，ADR-0022 决策 9；M26 工单 05 增强）：当前会话 →
 * 人读 Markdown 或 JSONL 原样副本。渲染核心写 {@link Appendable}（流式——CLI
 * 写盘与 Web 下载不整包驻内存），字符串形态为兼容重载。
 *
 * <p>markdown 结构：头部元信息（id/标题/导出时间）→ 按事件序的角色/时间戳正文
 * （用户/助手全文、工具调用一行摘要、压缩点一行标注、子代理回答按消息渲染）→
 * 交付清单（模型 deliverable/presented 声明聚合，M26-04）→ 变更摘要（系统对账
 * ——git 对账行或文件工具记录，数据由 agent 侧 ChangeSummary 供给，本类只渲染）
 * → 尾部附件引用清单。jsonl 为 {@link Session#jsonlLines()} 的逐行写出——落盘的
 * 原样副本。</p>
 */
public final class SessionExport {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 参数/结果摘要的单行截断上限。 */
    static final int SUMMARY_MAX = 160;

    private SessionExport() { }

    /** 导出格式（/export 参数面）。 */
    public enum Format {
        /** 人读 Markdown（缺省）。 */
        MARKDOWN("markdown", ".md"),
        /** JSONL 原样副本。 */
        JSON("json", ".jsonl");

        public final String argName;
        public final String fileSuffix;

        Format(String argName, String fileSuffix) {
            this.argName = argName;
            this.fileSuffix = fileSuffix;
        }

        /** 解析 /export 参数：空串 = 缺省 markdown；未知值 null（调用方点名）。 */
        public static Format parse(String arg) {
            String normalized = arg == null ? "" : arg.strip().toLowerCase(java.util.Locale.ROOT);
            if (normalized.isEmpty() || normalized.equals(MARKDOWN.argName)) {
                return MARKDOWN;
            }
            return normalized.equals(JSON.argName) ? JSON : null;
        }
    }

    /** 导出文件名：duo-session-&lt;id&gt;.md / .jsonl。 */
    public static String fileName(String sessionId, Format format) {
        return "duo-session-" + sessionId + format.fileSuffix;
    }

    /** 变更对账单行：路径 + 加减行数（binary 行为 null，显示 "(binary)"）。 */
    public record ChangeRow(String path, String added, String deleted) {
    }

    /**
     * 变更摘要数据（M26 工单 05）：git 对账（会话首尾快照比对，agent 侧
     * ChangeSummary 供给——本模块纯渲染不做 I/O）与文件工具记录双源。
     *
     * @param gitAvailable git 对账可用（快照在册且目录为 git 仓库）；false 时仅工具记录
     * @param gitRows      git 对账行（tracked 改动 + 新增未跟踪文件；空 = 无文件变更）
     * @param toolPaths    文件工具（write/edit）触碰过的路径聚合（永远可用的兜底清单）
     */
    public record ChangeReport(boolean gitAvailable, List<ChangeRow> gitRows,
                               List<String> toolPaths) {

        /** 空报告（无快照信息——退化为纯工具记录形态）。 */
        public static final ChangeReport NONE =
                new ChangeReport(false, List.of(), List.of());

        public ChangeReport {
            gitRows = List.copyOf(gitRows);
            toolPaths = List.copyOf(toolPaths);
        }
    }

    // ---- 字符串形态（兼容重载） ----

    /** markdown 人读导出（无变更摘要数据——交付清单照常渲染）。 */
    public static String markdown(Session session) {
        return markdown(session, ChangeReport.NONE);
    }

    /** markdown 人读导出（携变更摘要）。 */
    public static String markdown(Session session, ChangeReport report) {
        StringBuilder out = new StringBuilder();
        try {
            renderMarkdown(session, report, out);
        } catch (IOException e) {
            throw new IllegalStateException("markdown 渲染失败（StringBuilder 不抛 IO）", e);
        }
        return out.toString();
    }

    /** JSONL 原样副本（逐行等价于会话日志文件）。 */
    public static String jsonl(Session session) {
        StringBuilder out = new StringBuilder();
        try {
            renderJsonl(session, out);
        } catch (IOException e) {
            throw new IllegalStateException("jsonl 渲染失败（StringBuilder 不抛 IO）", e);
        }
        return out.toString();
    }

    // ---- 流式渲染核心（CLI 写盘 / Web 下载共用，不整包驻内存） ----

    /** markdown 流式渲染。 */
    public static void renderMarkdown(Session session, ChangeReport report,
                                      Appendable out) throws IOException {
        out.append("# duo 会话导出：").append(session.title() != null ? session.title() : session.id())
                .append('\n');
        out.append("\n- 会话 id: `").append(session.id()).append('`');
        if (session.title() != null) {
            out.append("\n- 标题: ").append(session.title());
        }
        out.append("\n- 导出时间: ").append(TIME.format(Instant.now()));
        out.append("\n- 事件数: ").append(String.valueOf(session.events().size())).append('\n');
        if (session.skippedCorruptLines() > 0) {
            // 坏行明示（BUG-20261002-07 症状③）：交付物对数据失真如实标注，不静默
            out.append("\n- 坏行跳过: ").append(String.valueOf(session.skippedCorruptLines()))
                    .append(" 条（无法解析为事件的日志行，未计入事件数）\n");
        }
        out.append("\n---\n");
        List<String> attachments = new ArrayList<>();
        for (SessionEvent event : session.events()) {
            appendEvent(out, event, attachments);
        }
        appendDeliverables(session, out);
        appendChangeSummary(report, out);
        out.append("\n---\n\n## 附件引用清单\n");
        if (attachments.isEmpty()) {
            out.append("\n无\n");
        } else {
            for (String line : attachments) {
                out.append("- ").append(line).append('\n');
            }
        }
    }

    /** JSONL 流式渲染（逐行写出，落盘原样副本）。 */
    public static void renderJsonl(Session session, Appendable out) throws IOException {
        for (String line : session.jsonlLines()) {
            out.append(line).append('\n');
        }
    }

    /**
     * 交付清单章节（M26 工单 04 数据源）：deliverable/presented 事件聚合——跨多次
     * 声明去重保序（同一文件多次交付只列一次）。无声明整个章节省略（无交付的会话
     * 不给空章节）。
     */
    private static void appendDeliverables(Session session, Appendable out) throws IOException {
        Set<String> unique = new LinkedHashSet<>();
        for (SessionEvent event : session.events()) {
            if (!SessionEvent.DELIVERABLE_PRESENTED.equals(event.type())) {
                continue;
            }
            collectPaths(event.text(), unique);
        }
        List<String> paths = List.copyOf(unique);
        if (paths.isEmpty()) {
            return;
        }
        out.append("\n---\n\n## 交付清单（模型声明）\n\n");
        for (String path : paths) {
            out.append("- `").append(path).append("`\n");
        }
    }

    /** 变更摘要章节：git 对账行（含行数）或文件工具记录（无行数）双形态。 */
    private static void appendChangeSummary(ChangeReport report, Appendable out)
            throws IOException {
        out.append("\n---\n\n## 变更摘要（系统对账）\n");
        if (report.gitAvailable()) {
            if (!report.gitRows().isEmpty()) {
                out.append("\n| 文件 | 新增 | 删除 |\n|---|---|---|\n");
                for (ChangeRow row : report.gitRows()) {
                    out.append("| `").append(row.path()).append("` | ")
                            .append(row.added() == null ? "(binary)" : row.added()).append(" | ")
                            .append(row.deleted() == null ? "(binary)" : row.deleted())
                            .append(" |\n");
                }
            } else {
                out.append("\ntracked 文件无变更\n");
            }
            if (!report.toolPaths().isEmpty()) {
                // 工具触碰与 git 对账口径不同（gitignored/既有未跟踪文件的改动只在此可见）
                out.append("\n另经文件工具触碰（含于上表或未在 git 跟踪内）：\n");
                for (String path : report.toolPaths()) {
                    out.append("- `").append(path).append("`\n");
                }
            }
            if (report.gitRows().isEmpty() && report.toolPaths().isEmpty()) {
                out.append("\n无文件变更\n");
            }
            return;
        }
        // 非 git 目录 / 快照不可用：工具记录形态（有清单无行数）
        out.append("\n（非 git 目录或快照不可用——仅文件工具记录，无行数统计）\n");
        if (report.toolPaths().isEmpty()) {
            out.append("\n无文件变更\n");
            return;
        }
        for (String path : report.toolPaths()) {
            out.append("- `").append(path).append("`\n");
        }
    }

    /** 交付声明事件的路径收集（解析归 SessionEvent.jsonStringArray 单一实现）。 */
    private static void collectPaths(String filesJson, Set<String> out) {
        out.addAll(SessionEvent.jsonStringArray(filesJson));
    }

    private static void appendEvent(Appendable out, SessionEvent event,
                                    List<String> attachments) throws IOException {
        String at = TIME.format(Instant.ofEpochMilli(event.at()));
        switch (event.type()) {
            case SessionEvent.USER_MESSAGE ->
                    appendTurn(out, "用户", at, event.text());
            case SessionEvent.ASSISTANT_MESSAGE ->
                    appendTurn(out, "助手", at, event.text());
            case SessionEvent.SUBAGENT_COMPLETED ->
                    appendTurn(out, "子代理回答", at, event.text());
            case SessionEvent.TOOL_CALL -> {
                out.append("\n### 工具 ").append(event.toolName() != null ? event.toolName() : "?")
                        .append(" · ").append(at).append('\n');
                out.append("- 参数: ").append(summarize(event.text())).append('\n');
            }
            case SessionEvent.TOOL_RESULT -> {
                out.append("- 结果: ").append(summarize(event.text())).append('\n');
                collectReadImageRef(event.text(), attachments);
            }
            case SessionEvent.USER_ATTACHMENT -> {
                out.append("\n> [附件] ").append(summarize(event.text())).append('\n');
                collectAttachmentRef(event.text(), attachments);
            }
            case SessionEvent.COMPACTION ->
                    out.append("\n> [压缩点 · ").append(event.toolName()).append(" · ").append(at)
                            .append("] 早期历史已折叠为摘要（全文见 JSONL 导出）\n");
            default -> {
                // chunk/审批/命令/权限档/标题/交付声明等治理与过程事件不进人读正文
                // （交付声明进交付清单章节；JSONL 导出全量可查）
            }
        }
    }

    private static void appendTurn(Appendable out, String role, String at, String text)
            throws IOException {
        out.append("\n## ").append(role).append(" · ").append(at).append("\n\n")
                .append(text.strip()).append('\n');
    }

    /** 单行摘要：换行折叠为 ⏎、超长截断加 …。 */
    static String summarize(String text) {
        String oneLine = text.strip().replaceAll("\r?\n", " ⏎ ");
        return oneLine.length() <= SUMMARY_MAX ? "`" + oneLine + "`"
                : "`" + oneLine.substring(0, SUMMARY_MAX) + "…`";
    }

    /** user/attachment 引用解析进尾部清单（坏引用跳过不炸导出）。 */
    private static void collectAttachmentRef(String text, List<String> attachments) {
        try {
            var parsed = AttachmentRef.from(text.strip());
            parsed.ifPresent(ref -> attachments.add("`" + ref.attachmentId() + "` "
                    + (ref.name() != null ? ref.name() + " " : "")
                    + (ref.mediaType() != null ? ref.mediaType() + " " : "")
                    + (ref.bytes() > 0 ? ref.bytes() + "B" : "")));
        } catch (Exception ignored) {
            attachments.add("(无法解析的附件引用) " + summarize(text));
        }
    }

    /** read_image 结果的入库标记行解析进尾部清单（提取器与 Session 单一事实来源）。 */
    private static void collectReadImageRef(String text, List<String> attachments) {
        AttachmentRef ref = Session.readImageRefOf(text);
        if (ref != null) {
            attachments.add("`" + ref.attachmentId() + "`（read_image 入库）");
        }
    }
}
