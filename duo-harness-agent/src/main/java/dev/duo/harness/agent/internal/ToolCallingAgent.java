package dev.duo.harness.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.ToolInvocation;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.session.Message;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * agent 循环实现：会话记录 + LLM 流式调用 + Function Calling 工具执行桥——
 * 模型发起 tool_calls 时逐个经六段管线执行，结果以 TOOL 消息回填并继续循环，
 * 直至模型给出最终回答或迭代上限触发。
 *
 * <p>治理链在此激活：工具执行走 {@code ToolsService.execute}（六段管线），
 * 审批拒绝 / guard 拦截 / 违约已由管线收敛为 error 结果——原样回填给 LLM，
 * 模型看到拒绝原因后自行调整行为（换方案 / 向用户解释），而非 harness 层终止。</p>
 *
 * <p>线程约定：实例非线程安全——单会话内串行使用。</p>
 */
public final class ToolCallingAgent implements ChatAgent {

    /** 最大迭代轮数（每轮 = 一次 LLM 调用往返；防异常任务无限循环烧 token）。 */
    static final int MAX_ITERATIONS = 10;

    private final LlmAdapter llm;
    private final ToolsService tools;
    private final Session session;
    private final String systemPrompt;
    private final int maxIterations;

    /** 便捷构造：迭代上限取默认值。 */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session, String systemPrompt) {
        this(llm, tools, session, systemPrompt, MAX_ITERATIONS);
    }

    /**
     * @param llm           LLM 流式适配器
     * @param tools         工具域服务（Function Calling 的执行后端）
     * @param session       会话（多轮记忆来源与事件落点）
     * @param systemPrompt  行为指令（每轮单独传，不进会话日志）
     * @param maxIterations 最大循环轮数（防死循环上限）
     */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session,
                            String systemPrompt, int maxIterations) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
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

        List<ToolInvocation> invocations = new ArrayList<>();
        StringBuilder finalReply = new StringBuilder();
        boolean completed = false;

        for (int iteration = 1; iteration <= maxIterations && !completed; iteration++) {
            LlmTurn turn = llm.streamTurn(buildRequest(), text -> {
                listener.onChunk(text);
                finalReply.append(text);
            });

            if (!turn.hasToolCalls()) {
                session.append(SessionEvent.assistantMessage(turn.text()));
                completed = true;
                break;
            }

            // 工具执行桥：tool_calls 逐个经六段管线执行，结果以 TOOL 消息回填
            for (ToolCallRequest call : turn.toolCalls()) {
                listener.onToolCall(call.name(), call.argumentsJson());
                ToolResult result = tools.execute(call.name(), argumentsAsJson(call.argumentsJson()));
                String resultText = String.valueOf(result.value());
                session.append(new SessionEvent(SessionEvent.TOOL_RESULT, System.currentTimeMillis(),
                        resultText));
                invocations.add(new ToolInvocation(call.name(), call.argumentsJson(),
                        resultText, result.isError()));
                listener.onToolResult(call.name(), resultText, result.isError());
            }
        }

        if (!completed) {
            String failure = "已达最大迭代轮数（" + maxIterations + "），共执行 "
                    + invocations.size() + " 次工具调用";
            return new AgentReply(failure, invocations, false);
        }
        return new AgentReply(finalReply.toString(), invocations, true);
    }

    /** 请求构造：system 指令 + 会话投影历史（本票无工具清单，留待工具执行桥工单扩展）。 */
    private ChatRequest buildRequest() {
        return new ChatRequest(systemPrompt, Messages.toChatMessages(session.deriveMessages()));
    }

    /** 参数 JSON 文本 → JsonNode（适配 ToolsService.execute 入参形态）。 */
    private JsonNode argumentsAsJson(String argumentsJson) {
        try {
            return new ObjectMapper().readTree(argumentsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("工具参数不是合法 JSON: " + argumentsJson, e);
        }
    }
}
