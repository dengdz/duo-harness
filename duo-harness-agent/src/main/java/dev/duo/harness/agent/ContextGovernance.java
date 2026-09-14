package dev.duo.harness.agent;

import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.session.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文治理管线（M9，ADR-0007 v3 具名整期）：模型上下文构造时刻的读侧转换——
 * spill（超大工具结果卸载）→ 修剪（次长结果头尾收窄）→ 计量 → compaction（远端
 * 历史折叠为摘要）。**第一性约束：治理只影响"模型看到什么"，不影响"日志记了
 * 什么"**——入参出参都是投影消息，JSONL 日志永远完整，回放与审计语义不动摇。
 *
 * <p>顺序即协同（DSH 经验）：spill 先卸能卸的 → pruner 收窄次长的 → 计量判断 →
 * 仍超预算才触发 compaction——修剪前置可能让压缩不必发生。阈值集中为本类常量
 * （四个数字先行，yml 化延后）。summary 生成复用 {@link LlmAdapter} 直答形态。</p>
 *
 * <p>线程约定：实例非线程安全——随 agent 循环串行使用。</p>
 */
public final class ContextGovernance {

    /** spill 触发阈值（字符）：超大工具结果卸载落盘，给模型预览 + 定位符。 */
    public static final int SPILL_THRESHOLD_CHARS = 50_000;

    /** 修剪触发阈值（字符）：spill 后仍超长的结果头尾收窄。 */
    public static final int PRUNE_THRESHOLD_CHARS = 8_000;

    /** compaction 触发比例：修剪后估算 token 超窗口 × 此比例时折叠远端历史。 */
    public static final double COMPACTION_THRESHOLD_RATIO = 0.8;

    /** 模型上下文窗口（token）：按主流 128K 量级取常量，窗口异型时改此处。 */
    public static final long CONTEXT_WINDOW_TOKENS = 128_000;

    /** compaction 保留近端原文比例（对齐 DSH 保留 16% 的量级）。 */
    public static final double KEEP_RECENT_RATIO = 0.2;

    /** compaction 触发的最小远端消息数：太少没有折叠价值（近端之外寥寥数条）。 */
    static final int MIN_REMOTE_MESSAGES = 4;

    /** spill 预览头长（字符）。 */
    static final int SPILL_PREVIEW_HEAD = 1_000;

    /** spill 预览尾长（字符）。 */
    static final int SPILL_PREVIEW_TAIL = 500;

    /** 修剪保留头长（字符）。 */
    static final int PRUNE_HEAD_CHARS = 2_000;

    /** 修剪保留尾长（字符）。 */
    static final int PRUNE_TAIL_CHARS = 1_000;

    private final LlmAdapter llm;

    public ContextGovernance(LlmAdapter llm) {
        this.llm = llm;
    }

    /**
     * 治理管线入口：投影消息进、投影消息出——出参即"模型本轮看到的会话"。
     *
     * @param messages 会话投影（deriveMessages 产物，不可变列表）
     * @param session  所属会话（spill 落盘目录来源；仅读取元信息不写入事件）
     * @return 治理后的投影（可能原样返回——未触发任何治理时零开销透传）
     */
    public List<Message> govern(List<Message> messages, dev.duo.harness.session.Session session) {
        long tokens = ContextBudget.estimateMessageTokens(messages);
        List<Message> governed = compact(spillAndPrune(messages, session));
        long after = ContextBudget.estimateMessageTokens(governed);
        if (after != tokens) {
            System.out.println("[上下文治理] " + messages.size() + " 条消息：估算 "
                    + tokens + " → " + after + " tokens（会话 " + session.id() + "）");
        }
        return governed;
    }

    /** spill + 修剪：工具结果的体量治理——先卸能卸的（spill），再收窄次长的（修剪）。 */
    private List<Message> spillAndPrune(List<Message> messages, dev.duo.harness.session.Session session) {
        List<Message> result = messages;
        for (int i = 0; i < result.size(); i++) {
            Message message = result.get(i);
            if (message.role() != Message.Role.TOOL
                    || message.content() == null) {
                continue;
            }
            int length = message.content().length();
            if (length > SPILL_THRESHOLD_CHARS) {
                String replacement = spill(message, session);
                if (replacement == null) {
                    continue; // 卸载失败保留原结果（治理永不丢数据）
                }
                if (result == messages) {
                    result = new ArrayList<>(messages);
                }
                result.set(i, new Message(Message.Role.TOOL, replacement,
                        message.toolCallId(), null, null));
            } else if (length > PRUNE_THRESHOLD_CHARS) {
                if (result == messages) {
                    result = new ArrayList<>(messages);
                }
                result.set(i, new Message(Message.Role.TOOL, prune(message.content()),
                        message.toolCallId(), null, null));
            }
        }
        return result;
    }

    /** 修剪：头 2K + 标注 + 尾 1K（次长结果的体量收窄；原文仍在 JSONL）。 */
    private String prune(String content) {
        int middle = content.length() - PRUNE_HEAD_CHARS - PRUNE_TAIL_CHARS;
        System.out.println("[上下文治理] 工具结果 " + content.length() + " 字符超 "
                + PRUNE_THRESHOLD_CHARS + "，修剪中段 " + middle + " 字符");
        return content.substring(0, PRUNE_HEAD_CHARS)
                + "\n…[已修剪中段 " + middle + " 字符，完整原文在会话日志中]…\n"
                + content.substring(content.length() - PRUNE_TAIL_CHARS);
    }

    /** 单条 spill：原文落盘，返回"预览 + 定位符"替换文本；失败返回 null。 */
    private String spill(Message message, dev.duo.harness.session.Session session) {
        try {
            Path spillDir = session.jsonl().getParent()
                    .resolve(session.id()).resolve("spill");
            Files.createDirectories(spillDir);
            int sequence = nextSequence(spillDir);
            // 投影的 TOOL 消息不带工具名（只携 toolCallId）——文件名以调用 id 标识
            String callId = message.toolCallId() == null ? "call" : message.toolCallId();
            Path file = spillDir.resolve(sequence + "-" + sanitize(callId) + ".txt");
            Files.writeString(file, message.content());
            String content = message.content();
            String preview = content.substring(0, SPILL_PREVIEW_HEAD) + "\n…[中间 "
                    + (content.length() - SPILL_PREVIEW_HEAD - SPILL_PREVIEW_TAIL) + " 字符已卸载]…\n"
                    + content.substring(content.length() - SPILL_PREVIEW_TAIL);
            System.out.println("[上下文治理] 工具结果 " + content.length() + " 字符超 "
                    + SPILL_THRESHOLD_CHARS + "，已卸载 " + file);
            return preview + "\n[完整原文已落盘: " + file + "，需要更多内容时请向用户询问该文件路径]";
        } catch (IOException e) {
            System.out.println("[上下文治理] 卸载失败，保留原结果: " + e.getMessage());
            return null;
        }
    }

    /** spill 目录内下一个序号（按现有文件数递增）。 */
    private int nextSequence(Path spillDir) throws IOException {
        try (var list = Files.list(spillDir)) {
            return (int) list.count() + 1;
        }
    }

    /** 文件名安全化：工具名只留单词字符。 */
    private static String sanitize(String toolName) {
        return toolName.replaceAll("[^\\w.-]", "_");
    }

    /** compaction：远端历史折叠（工单 04 实现，当前直通）。 */
    private List<Message> compact(List<Message> messages) {
        return messages;
    }

    /** compaction 摘要生成：远端消息经 LLM 直答折叠为固定骨架摘要（工单 04 启用）。 */
    String summarize(List<Message> remote) {
        ChatRequest request = new ChatRequest(SUMMARY_SYSTEM, toChatMessages(remote), List.of());
        StringBuilder summary = new StringBuilder();
        llm.stream(request, (ChatChunk chunk) -> summary.append(chunk.text()));
        return summary.toString();
    }

    /** 投影 → llm 消息（与 internal.Messages 同映射；摘要调用复用，不依赖 internal）。 */
    private static List<ChatMessage> toChatMessages(List<Message> messages) {
        List<ChatMessage> result = new ArrayList<>();
        for (Message message : messages) {
            result.add(new ChatMessage(ChatMessage.Role.USER, message.content(), null, null));
        }
        return result;
    }

    /** 摘要生成的固定骨架（对齐 DSH checkpoint 思路：结构固定便于模型稳定产出）。 */
    static final String SUMMARY_SYSTEM =
            "你是会话压缩器。把给定历史对话折叠为一份摘要，严格使用以下四个小节（markdown 二级标题），"
                    + "不添加其他内容、不发明未出现的事实：\n"
                    + "## 主要请求\n## 关键结论\n## 已做操作\n## 未决事项";
}
