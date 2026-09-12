package dev.duo.harness.session;

import java.util.Objects;

/**
 * 会话事件：追加式日志的唯一条目形态。
 *
 * <p>M4 三种事件载荷同构（type + at + text）；M5 起工具事件额外携带
 * {@code toolCallId} / {@code toolName}（协议要求 tool 结果消息与模型发起的
 * tool_calls 按 id 关联）。JSONL 按 {@code type} 字符串判别，可选字段缺省
 * 不破坏旧会话文件（追加写入向后兼容）。</p>
 *
 * @param type       事件类型（见本类常量）
 * @param at         事件时间戳（epoch millis）
 * @param text       事件载荷文本（tool/call 为参数 JSON；tool/result 为结果文本）
 * @param toolCallId 协议关联 id（仅工具事件携带，其余为 null）
 * @param toolName   工具名（仅工具事件携带，其余为 null）
 */
public record SessionEvent(String type, long at, String text, String toolCallId, String toolName) {

    /** 用户消息（每轮用户输入的完整文本）。 */
    public static final String USER_MESSAGE = "user/message";

    /** 助手流式增量（LLM 每段输出；投影时不入消息列表）。 */
    public static final String ASSISTANT_CHUNK = "assistant/chunk";

    /** 助手完整消息（全部 chunk 拼接后的最终文本）。 */
    public static final String ASSISTANT_MESSAGE = "assistant/message";

    /** 工具调用开始（text = 参数 JSON；toolCallId/toolName 携带关联信息）。 */
    public static final String TOOL_CALL = "tool/call";

    /** 工具调用结果（text = 结果文本；失败为错误说明）。 */
    public static final String TOOL_RESULT = "tool/result";

    /** 构造时校验非空——错误前移到构造点。 */
    public SessionEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        if (at < 0) {
            throw new IllegalArgumentException("at 不能为负: " + at);
        }
    }

    /** 兼容构造：非工具事件（无关联信息）。 */
    public SessionEvent(String type, long at, String text) {
        this(type, at, text, null, null);
    }

    /** 便捷工厂：当前时刻的用户消息。 */
    public static SessionEvent userMessage(String text) {
        return new SessionEvent(USER_MESSAGE, System.currentTimeMillis(), text);
    }

    /** 便捷工厂：当前时刻的助手流式增量。 */
    public static SessionEvent assistantChunk(String text) {
        return new SessionEvent(ASSISTANT_CHUNK, System.currentTimeMillis(), text);
    }

    /** 便捷工厂：当前时刻的助手完整消息。 */
    public static SessionEvent assistantMessage(String text) {
        return new SessionEvent(ASSISTANT_MESSAGE, System.currentTimeMillis(), text);
    }

    /** 便捷工厂：工具调用开始（id 关联模型发起的调用）。 */
    public static SessionEvent toolCall(String toolCallId, String toolName, String argsJson) {
        return new SessionEvent(TOOL_CALL, System.currentTimeMillis(), argsJson, toolCallId, toolName);
    }

    /** 便捷工厂：工具调用结果（id 关联模型发起的调用）。 */
    public static SessionEvent toolResult(String toolCallId, String toolName, String resultText) {
        return new SessionEvent(TOOL_RESULT, System.currentTimeMillis(), resultText, toolCallId, toolName);
    }
}
