package dev.duo.harness.agent.internal;

import dev.duo.harness.session.AttachmentRef;
import dev.duo.harness.attachment.RequestVariant;
import dev.duo.harness.attachment.RequestVariants;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.MessageImage;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.session.Message;
import dev.duo.harness.session.ToolCall;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 会话投影 → llm 消息的转换（唯一映射点；tool 消息携带协议关联 id）。 */
final class Messages {

    private Messages() {
    }

    /** 会话投影消息逐条转换为 llm 契约消息（纯文本形态——vision 部署走带参重载）。 */
    static List<ChatMessage> toChatMessages(List<Message> messages) {
        return toChatMessages(messages, null, false);
    }

    /**
     * 会话投影消息逐条转换为 llm 契约消息（含 Function Calling 与多部件图片形态）。
     *
     * @param variants 附件引用的请求变体解析器（null = 视觉未启用，附件引用丢弃——
     *                 上游闸门已拦，此处是防线末端而非执法点）
     * @param vision   视觉开关（llm.vision）：true 时引用解析为 base64 图片部件
     */
    static List<ChatMessage> toChatMessages(List<Message> messages,
                                            RequestVariants variants, boolean vision) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (Message message : messages) {
            if (message.role() == Message.Role.TOOL) {
                chatMessages.add(convertTool(message, variants, vision));
                continue;
            }
            if (message.toolCalls() != null) {
                List<ToolCallRequest> calls = message.toolCalls().stream()
                        .map(call -> new ToolCallRequest(call.id(), call.name(), call.argumentsJson()))
                        .toList();
                chatMessages.add(ChatMessage.assistantWithToolCalls(
                        message.content(), calls, message.reasoning()));
                continue;
            }
            List<MessageImage> images = resolveImages(message.attachments(), variants, vision);
            if (images != null) {
                chatMessages.add(ChatMessage.user(message.content(), images));
                continue;
            }
            chatMessages.add(new ChatMessage(wireRole(message.role()), message.content(), null, null));
        }
        return chatMessages;
    }

    /** 附件引用 → base64 图片部件（vision 关闭或解析器缺席时丢弃——上游闸门已拦）。 */
    private static List<MessageImage> resolveImages(List<AttachmentRef> refs,
                                                    RequestVariants variants, boolean vision) {
        if (refs == null || refs.isEmpty() || variants == null || !vision) {
            return null;
        }
        List<MessageImage> images = new ArrayList<>();
        for (AttachmentRef ref : refs) {
            RequestVariant variant = variants.variantFor(ref.attachmentId());
            images.add(new MessageImage(
                    Base64.getEncoder().encodeToString(variant.bytes()), variant.mediaType()));
        }
        return images;
    }

    private static ChatMessage convertTool(Message message, RequestVariants variants, boolean vision) {
        List<MessageImage> images = resolveImages(message.attachments(), variants, vision);
        return images == null
                ? ChatMessage.tool(message.toolCallId(), message.content())
                : ChatMessage.toolWithImages(message.toolCallId(), message.content(), images);
    }

    private static ChatMessage.Role wireRole(Message.Role role) {
        return switch (role) {
            case USER -> ChatMessage.Role.USER;
            case ASSISTANT -> ChatMessage.Role.ASSISTANT;
            case TOOL -> ChatMessage.Role.TOOL;
        };
    }
}
