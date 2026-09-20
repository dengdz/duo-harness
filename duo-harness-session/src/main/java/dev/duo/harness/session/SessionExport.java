package dev.duo.harness.session;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 会话导出渲染（M21 工单 09，ADR-0022 决策 9）：当前会话 → 人读 Markdown 或
 * JSONL 原样副本。纯函数（输入会话快照、输出字符串），零 I/O——落盘/下载由
 * 呈现位负责（CLI 写盘 cwd、Web 下载流）。
 *
 * <p>markdown 结构：头部元信息（id/标题/导出时间）→ 按事件序的角色/时间戳正文
 * （用户/助手全文、工具调用一行摘要、压缩点一行标注、子代理回答按消息渲染）→
 * 尾部附件引用清单（user/attachment 引用 + read_image 入库引用；字节不打包，
 * 库内永不删除）。jsonl 为 {@link Session#jsonlLines()} 的换行拼接——落盘的
 * 原样副本。</p>
 */
public final class SessionExport {

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

    /** markdown 人读导出。 */
    public static String markdown(Session session) {
        StringBuilder out = new StringBuilder();
        out.append("# duo 会话导出：").append(session.title() != null ? session.title() : session.id())
                .append('\n');
        out.append("\n- 会话 id: `").append(session.id()).append('`');
        if (session.title() != null) {
            out.append("\n- 标题: ").append(session.title());
        }
        out.append("\n- 导出时间: ").append(TIME.format(Instant.now()));
        out.append("\n- 事件数: ").append(session.events().size()).append('\n');
        out.append("\n---\n");
        List<String> attachments = new java.util.ArrayList<>();
        for (SessionEvent event : session.events()) {
            appendEvent(out, event, attachments);
        }
        out.append("\n---\n\n## 附件引用清单\n");
        if (attachments.isEmpty()) {
            out.append("\n无\n");
        } else {
            for (String line : attachments) {
                out.append("- ").append(line).append('\n');
            }
        }
        return out.toString();
    }

    /** JSONL 原样副本（逐行等价于会话日志文件）。 */
    public static String jsonl(Session session) {
        return String.join("\n", session.jsonlLines()) + (session.jsonlLines().isEmpty() ? "" : "\n");
    }

    private static void appendEvent(StringBuilder out, SessionEvent event,
                                    List<String> attachments) {
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
                // chunk/审批/命令/权限档/标题等治理与过程事件不进人读正文（JSONL 导出全量可查）
            }
        }
    }

    private static void appendTurn(StringBuilder out, String role, String at, String text) {
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

    /** read_image 结果的入库标记行解析进尾部清单（标记常量与 Session 单一事实来源）。 */
    private static void collectReadImageRef(String text, List<String> attachments) {
        for (String line : text.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith(Session.READ_IMAGE_REF_MARKER)) {
                attachments.add("`" + stripped.substring(Session.READ_IMAGE_REF_MARKER.length())
                        .split("——")[0].split(" ")[0].strip() + "`（read_image 入库）");
            }
        }
    }
}
