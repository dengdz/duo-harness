package dev.duo.harness.agent.internal;

import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.session.Message;
import dev.duo.harness.session.ToolCall;

import java.util.List;

/** 会话投影 → llm 消息的转换（唯一映射点；tool 消息携带协议关联 id）。 */
final class Messages {

    private Messages() {
    }

    /** 会话投影消息逐条转换为 llm 契约消息（含 Function Calling 形态）。 */
    static List<ChatMessage> toChatMessages(List<Message> messages) {
        return messages.stream().map(message -> {
            if (message.role() == Message.Role.TOOL) {
                return ChatMessage.tool(message.toolCallId(), message.content());
            }
            if (message.toolCalls() != null) {
                List<ToolCallRequest> calls = message.toolCalls().stream()
                        .map(call -> new ToolCallRequest(call.id(), call.name(), call.argumentsJson()))
                        .toList();
                return ChatMessage.assistantWithToolCalls(message.content(), calls);
            }
            return new ChatMessage(wireRole(message.role()), message.content(), null, null);
        }).toList();
    }

    private static ChatMessage.Role wireRole(Message.Role role) {
        return switch (role) {
            case USER -> ChatMessage.Role.USER;
            case ASSISTANT -> ChatMessage.Role.ASSISTANT;
            case TOOL -> ChatMessage.Role.TOOL;
        };
    }
}
