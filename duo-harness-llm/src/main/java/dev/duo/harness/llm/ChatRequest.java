package dev.duo.harness.llm;

import java.util.Objects;

/**
 * LLM 调用的 provider 中立契约：一次对话请求的输入。
 *
 * <p>M3 形态为最小闭环——system 指令 + 单条用户消息（无历史、无工具清单）。
 * M4 接会话时扩展为消息列表，M5 扩展工具清单——字段演进不改变"适配器把
 * 请求翻译为 provider 协议"的职责边界。</p>
 *
 * @param systemPrompt 行为指令（M3 为 yml 固定字符串）
 * @param userMessage  用户消息
 */
public record ChatRequest(String systemPrompt, String userMessage) {

    /** 构造时校验非空——错误前移到构造点。 */
    public ChatRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userMessage, "userMessage");
    }
}
