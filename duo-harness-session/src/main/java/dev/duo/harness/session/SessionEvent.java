package dev.duo.harness.session;

import java.util.Objects;

/**
 * 会话事件：追加式日志的唯一条目形态。
 *
 * <p>三种事件载荷同构（type + at + text），JSONL 按 {@code type} 字符串判别——
 * 追加写入天然向后兼容：后续里程碑新增事件类型（如 tool/call）不破坏旧会话文件
 * （里程碑序列见 docs/adr/0007-里程碑重排agent循环提前.md）。</p>
 *
 * @param type 事件类型（见本类常量）
 * @param at   事件时间戳（epoch millis）
 * @param text 事件载荷文本
 */
public record SessionEvent(String type, long at, String text) {

    /** 用户消息（每轮用户输入的完整文本）。 */
    public static final String USER_MESSAGE = "user/message";

    /** 助手流式增量（LLM 每段输出；投影时不入消息列表）。 */
    public static final String ASSISTANT_CHUNK = "assistant/chunk";

    /** 助手完整消息（全部 chunk 拼接后的最终文本）。 */
    public static final String ASSISTANT_MESSAGE = "assistant/message";

    /** 工具调用开始（载荷：工具名 + 参数 JSON 的组合文本）。 */
    public static final String TOOL_CALL = "tool/call";

    /** 工具调用结果（载荷：结果文本；失败为错误说明）。 */
    public static final String TOOL_RESULT = "tool/result";

    /** 构造时校验非空——错误前移到构造点。 */
    public SessionEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        if (at < 0) {
            throw new IllegalArgumentException("at 不能为负: " + at);
        }
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
}
