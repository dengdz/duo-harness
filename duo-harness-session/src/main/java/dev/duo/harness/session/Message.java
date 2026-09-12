package dev.duo.harness.session;

import java.util.List;
import java.util.Objects;

/**
 * 投影产物：发给 LLM 的对话消息形态。
 *
 * <p>三种形态（按 role 对应）：</p>
 * <ul>
 *   <li>{@code USER} / {@code ASSISTANT}：纯文本消息（content）；</li>
 *   <li>{@code ASSISTANT} + toolCalls：模型请求执行的工具调用（Function Calling）；</li>
 *   <li>{@code TOOL}：工具结果回填（content + toolCallId 关联）。</li>
 * </ul>
 *
 * <p>由 {@link Session#deriveMessages()} 从事件日志派生：`user/message` 与
 * `assistant/message` 投影为纯文本消息；`tool/call` 投影为带 toolCalls 的
 * ASSISTANT 消息；`tool/result` 投影为 TOOL 消息。</p>
 */
public record Message(Role role, String content, String toolCallId, List<ToolCall> toolCalls) {

    /** 消息角色。 */
    public enum Role { USER, ASSISTANT, TOOL }

    /** 构造时校验非空与防御性拷贝——错误前移到构造点。 */
    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
    }

    /** 兼容构造：纯文本消息（无工具调用信息）。 */
    public Message(Role role, String content) {
        this(role, content, null, null);
    }

    /** TOOL 角色的便捷工厂：工具结果回填（id 关联模型发起的调用）。 */
    public static Message tool(String toolCallId, String content) {
        return new Message(Role.TOOL, content, toolCallId, null);
    }

    /** ASSISTANT 角色的便捷工厂：携带模型请求的工具调用。 */
    public static Message assistantWithToolCalls(List<ToolCall> toolCalls) {
        // assistant 工具调用消息的 content 为空串（模型调用工具时通常无文本输出）
        return new Message(Role.ASSISTANT, "", null, toolCalls);
    }
}
