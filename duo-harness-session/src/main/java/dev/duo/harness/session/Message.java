package dev.duo.harness.session;

import java.util.List;
import java.util.Objects;

/**
 * 投影产物：发给 LLM 的对话消息形态。
 *
 * <p>四种形态（按 role 对应）：</p>
 * <ul>
 *   <li>{@code USER}：文本消息，可携带附件引用（images——附件库中的图片，M21）；</li>
 *   <li>{@code ASSISTANT}：纯文本消息或 + toolCalls（模型请求执行的工具调用，
 *       可携带思考内容 reasoning）；</li>
 *   <li>{@code TOOL}：工具结果回填（content + toolCallId 关联），read_image 的
 *       结果携带附件引用（模型由此"看见"图片，M21）。</li>
 * </ul>
 *
 * <p>由 {@link Session#deriveMessages()} 从事件日志派生：`user/attachment` 引用
 * 挂到紧随其后的 `user/message`；read_image 的 tool/result 由文本约定解析出引用。</p>
 */
public record Message(Role role, String content, String toolCallId, List<ToolCall> toolCalls,
                      String reasoning, List<AttachmentRef> attachments) {

    /** 消息角色。 */
    public enum Role { USER, ASSISTANT, TOOL }

    /** 构造时校验非空与防御性拷贝——错误前移到构造点。 */
    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        attachments = attachments == null ? null : List.copyOf(attachments);
    }

    /** 兼容构造：纯文本消息（无工具调用信息、无附件引用）。 */
    public Message(Role role, String content) {
        this(role, content, null, null, null, null);
    }

    /** 兼容构造：无思考内容。 */
    public Message(Role role, String content, String toolCallId, List<ToolCall> toolCalls) {
        this(role, content, toolCallId, toolCalls, null, null);
    }

    /** 兼容构造：无附件引用。 */
    public Message(Role role, String content, String toolCallId, List<ToolCall> toolCalls,
                   String reasoning) {
        this(role, content, toolCallId, toolCalls, reasoning, null);
    }

    /** USER 消息 + 附件引用（多部件投影的会话侧形态）。 */
    public static Message userWithAttachments(String content, List<AttachmentRef> attachments) {
        return new Message(Role.USER, content, null, null, null, attachments);
    }

    /** TOOL 角色的便捷工厂：工具结果回填（id 关联模型发起的调用）。 */
    public static Message tool(String toolCallId, String content) {
        return new Message(Role.TOOL, content, toolCallId, null, null, null);
    }

    /** ASSISTANT 角色的便捷工厂：携带模型请求的工具调用（无思考内容）。 */
    public static Message assistantWithToolCalls(List<ToolCall> toolCalls) {
        // assistant 工具调用消息的 content 为空串（模型调用工具时通常无文本输出）
        return new Message(Role.ASSISTANT, "", null, toolCalls, null);
    }

    /** ASSISTANT 角色 + 思考内容：思考模式 provider 要求工具调用轮的 reasoning 原样回传。 */
    public static Message assistantWithToolCalls(List<ToolCall> toolCalls, String reasoning) {
        return new Message(Role.ASSISTANT, "", null, toolCalls, reasoning);
    }

    /** TOOL 消息 + 附件引用（read_image 结果的图片引用，随请求多部件化）。 */
    public static Message toolWithAttachments(String toolCallId, String content,
                                              List<AttachmentRef> attachments) {
        return new Message(Role.TOOL, content, toolCallId, null, null, attachments);
    }

}
