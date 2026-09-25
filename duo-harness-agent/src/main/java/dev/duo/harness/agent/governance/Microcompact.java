package dev.duo.harness.agent.governance;

import dev.duo.harness.session.Message;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * microcompact 裁剪选择器（M25 工单 04，纯函数）：从投影中选出「最近 N 组之外的
 * 可压缩工具结果」名单——ZCode 同构：用户/助手文本不动（早期约束不丢），只把占
 * token 大头的旧工具结果清成占位标记。
 *
 * <p>分组锚 = USER 消息：一条用户消息起到下一条前的段落为一组，最近 {@code
 * keepRecent} 组完整保留。豁免（选择侧过滤）：工具不在白名单（大体量只读类才可
 * 清）、结果过短（清了无节省）、调用报告失败（error——排障依据保留）、携带附件
 * 引用（媒体上下文不清）。节省估算低于阈值时返回空名单（不值得一次裁剪痕）。</p>
 *
 * <p>线程约定：无状态静态纯函数。</p>
 */
final class Microcompact {

    /** 可压缩工具白名单：大体量只读类（write/edit/ask_user/todo/计划/memory_write 等语义件不在列）。 */
    private static final Set<String> CLEARABLE_TOOLS = Set.of(
            "read", "glob", "grep", "bash", "web_fetch", "web_search",
            "task-output", "session_search");

    /** 候选最小长度（字符）：更短的结果清除无节省价值，清了反伤语义。 */
    private static final int MIN_CANDIDATE_CHARS = 500;

    /** 触发本次裁剪的最小节省估算（token，字符/4 粗估）——ZCode below_min_savings 同构。 */
    private static final int MIN_SAVING_TOKENS = 256;

    private Microcompact() {
    }

    /**
     * 选出本次可清除的 toolCallId 名单（保持投影顺序）。
     *
     * @param messages        会话投影
     * @param toolMeta        callId → 工具元数据（工具名 + 失败标志；来源 = 会话事件流）
     * @param keepRecentGroups 完整保留的最近组数（组 = 一条用户消息起的段落）
     * @return 可清除名单（空 = 不值得裁剪，调用方放弃）
     */
    static List<String> select(List<Message> messages, Map<String, ToolMeta> toolMeta,
                               int keepRecentGroups) {
        Set<Integer> keepFrom = keepBoundaries(messages, keepRecentGroups);
        List<String> cleared = new ArrayList<>();
        long savingChars = 0;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message.role() != Message.Role.TOOL || message.toolCallId() == null
                    || keepFrom.contains(i) || seen.contains(message.toolCallId())) {
                continue;
            }
            ToolMeta meta = toolMeta.get(message.toolCallId());
            if (meta == null || meta.toolName() == null
                    || !CLEARABLE_TOOLS.contains(meta.toolName()) || meta.error()
                    || message.attachments() != null && !message.attachments().isEmpty()
                    || message.content() == null
                    || message.content().length() < MIN_CANDIDATE_CHARS) {
                continue;
            }
            cleared.add(message.toolCallId());
            seen.add(message.toolCallId());
            savingChars += message.content().length();
        }
        long savingTokens = savingChars / ContextBudget.CHARS_PER_TOKEN;
        return savingTokens >= MIN_SAVING_TOKENS ? cleared : List.of();
    }

    /** 最近 N 组的起始索引集合（组锚 = USER 消息；组内一切索引都算保留区）。 */
    private static Set<Integer> keepBoundaries(List<Message> messages, int keepRecentGroups) {
        Set<Integer> keepFrom = new HashSet<>();
        int userSeen = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            keepFrom.add(i);
            if (messages.get(i).role() == Message.Role.USER) {
                userSeen++;
                if (userSeen >= keepRecentGroups) {
                    break;
                }
            }
        }
        return keepFrom;
    }

    /** 工具调用元数据（选择与豁免判定用；来源 = 会话事件流倒查）。 */
    record ToolMeta(String toolName, boolean error) {
    }
}
