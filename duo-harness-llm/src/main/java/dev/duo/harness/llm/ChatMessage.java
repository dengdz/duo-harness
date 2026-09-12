package dev.duo.harness.llm;

import java.util.List;
import java.util.Objects;

/**
 * 对话消息：发给 LLM 的请求历史中的一条。
 *
 * <p>三种形态（按 role 对应）：</p>
 * <ul>
 *   <li>{@code USER} / {@code ASSISTANT}：纯文本消息（content）；</li>
 *   <li>{@code ASSISTANT} + toolCalls：模型发起的工具调用（Function Calling）；</li>
 *   <li>{@code TOOL}：工具结果回填（content + toolCallId 关联）。</li>
 * </ul>
 *
 * <p>system 指令不在此列表——由 {@link ChatRequest} 的 systemPrompt 字段单列。
 * 思考模型（thinking mode）的 {@code reasoningContent} 属 assistant 消息的可选
 * 回传字段——工具调用链中 DeepSeek 等兼容 provider 要求原样传回。</p>
 */
public record ChatMessage(Role role, String content, String toolCallId,
                          List<ToolCallRequest> toolCalls, String reasoningContent) {

    /** 消息角色（与 OpenAI 兼容协议的 role 字段对齐）。 */
    public enum Role {
        USER, ASSISTANT, TOOL;

        /** 协议 wire 值（OpenAI 兼容协议的 role 字段字符串，全小写）。 */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** 构造时校验非空与防御性拷贝——错误前移到构造点。 */
    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
    }

    /** 兼容构造：无思考内容。 */
    public ChatMessage(Role role, String content, String toolCallId, List<ToolCallRequest> toolCalls) {
        this(role, content, toolCallId, toolCalls, null);
    }

    /** USER 角色的便捷工厂。 */
    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null, null, null);
    }

    /** ASSISTANT 角色的便捷工厂（纯文本回复）。 */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, null, null, null);
    }

    /** TOOL 角色的便捷工厂：工具结果回填（id 关联模型发起的调用）。 */
    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, toolCallId, null, null);
    }

    /** ASSISTANT 角色 + 工具调用清单 + 思考内容（模型请求执行工具）。 */
    public static ChatMessage assistantWithToolCalls(String content, List<ToolCallRequest> toolCalls,
                                                     String reasoningContent) {
        return new ChatMessage(Role.ASSISTANT, content, null, toolCalls, reasoningContent);
    }
}
