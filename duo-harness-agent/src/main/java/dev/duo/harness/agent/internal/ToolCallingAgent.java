package dev.duo.harness.agent.internal;

import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.session.Message;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;

import java.util.List;
import java.util.Objects;

/**
 * agent 循环实现：会话记录 + LLM 流式直答路径（无工具直答）。
 *
 * <p>Function Calling 的工具执行桥尚未实现——请求的 tools 清单恒空，
 * 模型不会收到工具清单，也就不会发起调用；迭代上限常量先行定义，
 * 为循环扩展锚定语义。</p>
 *
 * <p>会话由构造注入（多轮记忆来自投影）；线程约定：实例非线程安全——
 * 单会话内串行使用。</p>
 */
public final class ToolCallingAgent implements ChatAgent {

    /** 最大迭代轮数（防异常任务无限循环烧 token；当前直答路径恒为一轮）。 */
    static final int MAX_ITERATIONS = 10;

    private final LlmAdapter llm;
    private final Session session;
    private final String systemPrompt;
    private final int maxIterations;

    /**
     * @param llm          LLM 流式适配器
     * @param session      会话（多轮记忆来源与事件落点）
     * @param systemPrompt 行为指令（每轮单独传，不进会话日志）
     * @param maxIterations 最大循环轮数（防死循环上限）
     */
    /** 便捷构造：迭代上限取默认值。 */
    public ToolCallingAgent(LlmAdapter llm, Session session, String systemPrompt) {
        this(llm, session, systemPrompt, MAX_ITERATIONS);
    }

    public ToolCallingAgent(LlmAdapter llm, Session session, String systemPrompt, int maxIterations) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.session = Objects.requireNonNull(session, "session");
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt 不能为空");
        }
        this.systemPrompt = systemPrompt;
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations 至少为 1: " + maxIterations);
        }
        this.maxIterations = maxIterations;
    }

    @Override
    public AgentReply send(String userText, AgentListener listener) {
        Objects.requireNonNull(listener, "listener");
        session.append(SessionEvent.userMessage(userText));

        StringBuilder reply = new StringBuilder();
        llm.stream(buildRequest(), chunk -> {
            listener.onChunk(chunk.text());
            reply.append(chunk.text());
        });

        String finalText = reply.toString();
        session.append(SessionEvent.assistantMessage(finalText));
        return new AgentReply(finalText, List.of(), true);
    }

    /** 请求构造：system 指令 + 会话投影历史（无工具直答路径）。 */
    private ChatRequest buildRequest() {
        return new ChatRequest(systemPrompt, Messages.toChatMessages(session.deriveMessages()));
    }
}
