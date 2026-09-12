package dev.duo.harness.session;

import java.util.Objects;

/**
 * 投影产物：发给 LLM 的对话消息形态（role + content）。
 *
 * <p>由 {@link Session#deriveMessages()} 从事件日志派生——`user/message` 事件
 * 投影为 USER、`assistant/message` 投影为 ASSISTANT；流式 chunk（assistant/chunk）
 * 是过程细节，不投影。</p>
 */
public record Message(Role role, String content) {

    /** 消息角色（与会话事件类型一一对应）。 */
    public enum Role { USER, ASSISTANT }

    /** 构造时校验非空——错误前移到构造点。 */
    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }
}
