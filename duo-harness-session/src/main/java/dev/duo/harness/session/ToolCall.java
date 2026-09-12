package dev.duo.harness.session;

import java.util.Objects;

/**
 * 工具调用引用：会话事件/投影消息中承载的一次工具调用描述（中立形态，不依赖 llm）。
 *
 * @param id            协议关联 id（tool 结果消息回填时携带）
 * @param name          工具名
 * @param argumentsJson 参数 JSON 文本
 */
public record ToolCall(String id, String name, String argumentsJson) {

    /** 构造时校验非空——错误前移到构造点。 */
    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
    }
}
