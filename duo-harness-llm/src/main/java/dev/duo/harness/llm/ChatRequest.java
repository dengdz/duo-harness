package dev.duo.harness.llm;

import java.util.List;
import java.util.Objects;

/**
 * LLM 调用的 provider 中立契约：一次对话请求的输入。
 *
 * <p>M5 形态——system 指令单列 + 按序对话历史 + 可选工具清单（Function
 * Calling）。历史由调用方从会话投影转换而来；工具清单由 agent 从工具域
 * 转换为 {@link ToolSpec}；适配器按序序列化为 provider 协议的消息数组。
 * system 指令不进消息列表、不进会话日志（每轮单独传）。</p>
 *
 * @param systemPrompt 行为指令（每轮单独传，不进会话日志）
 * @param messages     按序对话历史（不含 system）
 * @param tools        可用工具清单（空 = 无工具；本次调用不启用 Function Calling）
 */
public record ChatRequest(String systemPrompt, List<ChatMessage> messages, List<ToolSpec> tools) {

    /** 无工具直答构造（M3/M4 兼容形态）。 */
    public ChatRequest(String systemPrompt, List<ChatMessage> messages) {
        this(systemPrompt, messages, List.of());
    }

    /** 构造时校验非空与防御性拷贝——错误前移到构造点。 */
    public ChatRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(messages, "messages");
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
