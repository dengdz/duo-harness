package dev.duo.harness.agent.internal;

import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.session.Message;

import java.util.List;

/** 会话投影 → llm 消息的转换（多消费方共享的唯一映射点，TOOL 一一映射）。 */
final class Messages {

    private Messages() {
    }

    /** 会话投影消息逐条转换为 llm 契约消息（role 一一映射，无默认兜底）。 */
    static List<ChatMessage> toChatMessages(List<Message> messages) {
        return messages.stream()
                .map(message -> new ChatMessage(wireRole(message.role()), message.content(), null, null))
                .toList();
    }

    private static ChatMessage.Role wireRole(Message.Role role) {
        return switch (role) {
            case USER -> ChatMessage.Role.USER;
            case ASSISTANT -> ChatMessage.Role.ASSISTANT;
            case TOOL -> ChatMessage.Role.TOOL;
        };
    }
}
