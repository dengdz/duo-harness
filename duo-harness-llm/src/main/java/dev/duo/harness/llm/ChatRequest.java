package dev.duo.harness.llm;

import java.util.List;
import java.util.Objects;

/**
 * LLM 调用的 provider 中立契约：一次对话请求的输入。
 *
 * <p>M4 形态——system 指令单列 + 按序对话历史（多轮记忆）。历史由调用方
 * 从会话投影（deriveMessages）转换而来；适配器按序序列化为 provider 协议
 * 的消息数组。system 指令不进消息列表、不进会话日志（每轮单独传）。</p>
 *
 * @param systemPrompt 行为指令（每轮单独传，不进会话日志）
 * @param messages     按序对话历史（不含 system）
 */
public record ChatRequest(String systemPrompt, List<ChatMessage> messages) {

    /** 构造时校验非空与防御性拷贝——错误前移到构造点。 */
    public ChatRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(messages, "messages");
        messages = List.copyOf(messages);
    }
}
