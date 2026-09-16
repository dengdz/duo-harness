package dev.duo.harness.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.ToolInvocation;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.governance.ContextGovernance;
import dev.duo.harness.agent.prompt.PromptRegistry;
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
import dev.duo.harness.session.TokenUsage;
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
    public static final int MAX_ITERATIONS = 10;

    private final LlmAdapter llm;
    private final ToolsService tools;
    private final Session session;
    private final PromptRegistry prompts;
    private final int maxIterations;
    /** 上下文治理管线（M9；null = 未装配，投影直通——治理可选零残留）。 */
    private final ContextGovernance governance;

    /** 便捷构造：迭代上限取默认值，单一 system 提示（包装为用户指令片段）。 */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session, String systemPrompt) {
        this(llm, tools, session, new PromptRegistry(systemPrompt), MAX_ITERATIONS, null);
    }

    /** 便捷构造：单一 system 提示 + 显式迭代上限。 */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session,
                            String systemPrompt, int maxIterations) {
        this(llm, tools, session, new PromptRegistry(systemPrompt), maxIterations, null);
    }

    /** 便捷构造：prompt 注册表 + 默认迭代上限（无治理——投影直通）。 */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session, PromptRegistry prompts) {
        this(llm, tools, session, prompts, MAX_ITERATIONS, null);
    }

    /** 便捷构造：prompt 注册表 + 显式迭代上限（无治理——投影直通）。 */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session,
                            PromptRegistry prompts, int maxIterations) {
        this(llm, tools, session, prompts, maxIterations, null);
    }

    /**
     * 完整构造：含上下文治理管线（M9）。
     *
     * @param llm           LLM 流式适配器
     * @param tools         工具域服务（Function Calling 的执行后端）
     * @param session       会话（多轮记忆来源与事件落点）
     * @param prompts       prompt 注册表（每轮组装 system 提示）
     * @param maxIterations 最大循环轮数（防死循环上限）
     * @param governance    上下文治理管线（投影 → 管线 → 请求；null = 不治理）
     */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session,
                            PromptRegistry prompts, int maxIterations,
                            ContextGovernance governance) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.session = Objects.requireNonNull(session, "session");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations 至少为 1: " + maxIterations);
        }
        this.maxIterations = maxIterations;
        this.governance = governance;
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
                // provider 真实用量随 assistant/message 落日志（ADR-0009）：llm 域统计
                // 映射为会话事件词汇——治理与状态展示的取数源，provider 未报告为 null
                session.append(SessionEvent.assistantMessage(turn.text(),
                        turn.usage() == null ? null : new TokenUsage(
                                turn.usage().promptTokens(),
                                turn.usage().completionTokens(),
                                turn.usage().totalTokens())));
                completed = true;
                break;
            }

            // 工具执行桥：tool_calls 逐个经三段管线与治理链执行，结果以 TOOL 消息回填。
            // 思考内容随 tool/call 事件持久化——会话投影重建的请求历史天然完整
            // （思考模式 provider 要求历史工具调用消息回传 reasoning，ADR 见 BUG-20260913-03）
            for (ToolCallRequest call : turn.toolCalls()) {
                session.append(SessionEvent.toolCall(call.id(), call.name(), call.argumentsJson(),
                        turn.reasoningContent()));
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

    /** 请求构造：组装 system 提示 + 会话投影历史（过治理管线）+ 工具清单（Function Calling）。 */
    private ChatRequest buildRequest() {
        List<ToolSpec> specs = tools.list().stream()
                .map(def -> new ToolSpec(def.name(), def.description(),
                        def.parameters() == null ? "{}" : def.parameters().toString()))
                .toList();
        List<Message> projected = session.deriveMessages();
        if (governance != null) {
            projected = governance.govern(projected, session);
        }
        return new ChatRequest(prompts.compose(), Messages.toChatMessages(projected), specs);
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
