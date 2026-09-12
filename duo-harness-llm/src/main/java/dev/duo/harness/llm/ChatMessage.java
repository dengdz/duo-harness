package dev.duo.harness.llm;

import java.util.Objects;

/**
 * 对话消息：发给 LLM 的请求历史中的一条（role + content）。
 *
 * <p>role 与 OpenAI 兼容协议的消息角色对齐（system 消息由 {@link ChatRequest}
 * 的 systemPrompt 字段单列，不出现在消息列表中）。</p>
 *
 * @param role    消息角色（USER / ASSISTANT）
 * @param content 消息文本
 */
public record ChatMessage(Role role, String content) {

    /** 消息角色（与 OpenAI 兼容协议的 role 字段对齐）。 */
    public enum Role {
        USER, ASSISTANT;

        /** 协议 wire 值（OpenAI 兼容协议的 role 字段字符串）。 */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** USER 角色的便捷工厂。 */
    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    /** ASSISTANT 角色的便捷工厂。 */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }

    /** 构造时校验非空——错误前移到构造点。 */
    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }
}
