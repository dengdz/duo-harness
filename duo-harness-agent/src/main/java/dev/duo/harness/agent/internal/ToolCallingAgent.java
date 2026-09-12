package dev.duo.harness.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.ToolInvocation;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.PromptRegistry;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.llm.ToolSpec;
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
 * 模型发起 tool_calls 时逐个经工具域三段管线与治理链执行，结果以 TOOL 消息回填并继续循环，
 * 直至模型给出最终回答或迭代上限触发。
 *
 * <p>治理链在此激活：工具执行走 {@code ToolsService.execute}（三段瀑布管线，
 * 审批 / guard / 输出契约挂链生效），
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
    private final PromptRegistry prompts;
    private final int maxIterations;

    /** 便捷构造：迭代上限取默认值，单一 system 提示（包装为用户指令片段）。 */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session, String systemPrompt) {
        this(llm, tools, session, new PromptRegistry(systemPrompt), MAX_ITERATIONS);
    }

    /** 便捷构造：单一 system 提示 + 显式迭代上限。 */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session,
                            String systemPrompt, int maxIterations) {
        this(llm, tools, session, new PromptRegistry(systemPrompt), maxIterations);
    }

    /** 便捷构造：prompt 注册表 + 默认迭代上限。 */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session, PromptRegistry prompts) {
        this(llm, tools, session, prompts, MAX_ITERATIONS);
    }

    /**
     * @param llm           LLM 流式适配器
     * @param tools         工具域服务（Function Calling 的执行后端）
     * @param session       会话（多轮记忆来源与事件落点）
     * @param prompts       prompt 注册表（每轮组装 system 提示）
     * @param maxIterations 最大循环轮数（防死循环上限）
     */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session,
                            PromptRegistry prompts, int maxIterations) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.session = Objects.requireNonNull(session, "session");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
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
        // 上一轮 assistant(tool_calls) 的思考内容：思考模式 provider 要求下一轮请求原样传回
        String pendingReasoning = null;

        for (int iteration = 1; iteration <= maxIterations && !completed; iteration++) {
            LlmTurn turn = llm.streamTurn(withReasoning(buildRequest(), pendingReasoning), text -> {
                listener.onChunk(text);
                finalReply.append(text);
            });
            pendingReasoning = turn.reasoningContent();

            if (!turn.hasToolCalls()) {
                session.append(SessionEvent.assistantMessage(turn.text()));
                completed = true;
                break;
            }

            // 工具执行桥：tool_calls 逐个经三段管线与治理链执行，结果以 TOOL 消息回填
            for (ToolCallRequest call : turn.toolCalls()) {
                session.append(SessionEvent.toolCall(call.id(), call.name(), call.argumentsJson()));
                listener.onToolCall(call.name(), call.argumentsJson());
                ToolResult result = tools.execute(call.name(), argumentsAsJson(call.argumentsJson()));
                String resultText = String.valueOf(result.value());
                session.append(SessionEvent.toolResult(call.id(), call.name(), resultText));
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

    /** 请求构造：组装 system 提示 + 会话投影历史 + 工具清单（工具域全量，Function Calling）。 */
    private ChatRequest buildRequest() {
        List<ToolSpec> specs = tools.list().stream()
                .map(def -> new ToolSpec(def.name(), def.description(),
                        def.parameters() == null ? "{}" : def.parameters().toString()))
                .toList();
        return new ChatRequest(prompts.compose(), Messages.toChatMessages(session.deriveMessages()), specs);
    }

    /**
     * 思考内容回填：附加到最近一条 assistant(tool_calls) 消息——
     * 思考模式 provider（DeepSeek 等）对工具调用轮的 assistant 消息强制要求该字段，缺失即 400。
     */
    private ChatRequest withReasoning(ChatRequest request, String reasoning) {
        if (reasoning == null || reasoning.isBlank()) {
            return request;
        }
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.role() == ChatMessage.Role.ASSISTANT
                    && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                ChatMessage withReasoning = new ChatMessage(message.role(), message.content(),
                        message.toolCallId(), message.toolCalls(), reasoning);
                List<ChatMessage> copy = new ArrayList<>(messages);
                copy.set(i, withReasoning);
                return new ChatRequest(request.systemPrompt(), copy, request.tools());
            }
        }
        return request;
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
