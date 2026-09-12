package dev.duo.harness.llm;

import java.util.Objects;

/**
 * LLM 发起的一次工具调用请求（Function Calling）：模型决定调用某工具，
 * 携带协议关联 id 与 JSON 参数。
 *
 * <p>agent 循环按此执行工具（经治理管线），并以协议要求的 id 关联回填
 * 工具结果消息。</p>
 *
 * @param id            协议关联 id（回填 tool 结果消息时必须携带）
 * @param name          工具名
 * @param argumentsJson 参数 JSON 文本（模型生成，可能不完整需容错）
 */
public record ToolCallRequest(String id, String name, String argumentsJson) {

    /** 构造时校验非空——错误前移到构造点。 */
    public ToolCallRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
    }
}
