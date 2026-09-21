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
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * agent 循环实现：会话记录 + LLM 流式调用 + Function Calling 工具执行桥——
 * 模型发起 tool_calls 时按并发安全性分组执行（ADR-0018）：连续并发安全调用
 * 成组进虚拟线程滚动池并行执行，独占调用（未通过安全判定 / 需审批 / 未知工具）
 * 作为顺序屏障单独执行；无论完成先后，结果以 TOOL 消息按 model 序回填并继续循环，
 * 直至模型给出最终回答或迭代上限触发。
 *
 * <p>治理链在此激活：工具执行走 {@code ToolsService.execute}（三段瀑布管线，
 * 审批 / guard / 输出契约挂链生效），
 * 审批拒绝 / guard 拦截 / 违约已由管线收敛为 error 结果——原样回填给 LLM，
 * 模型看到拒绝原因后自行调整行为（换方案 / 向用户解释），而非 harness 层终止。</p>
 *
 * <p>线程约定：实例非线程安全——单会话内串行使用。{@code send} 仍是单线程入口；
 * 并发只发生在安全组的工具执行段（虚拟线程 fan-out），事件日志写入收敛回
 * {@code send} 线程单点提交（model 序成对提交，会话单写者约定不变）。</p>
 */
public final class ToolCallingAgent implements ChatAgent {

    /** 参数解析共享实例：ObjectMapper 创建重量级，热路径（每次工具调用）复用。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper TOOLS_ARGS_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 最大迭代轮数（每轮 = 一次 LLM 调用往返；防异常任务无限循环烧 token）。 */
    public static final int MAX_ITERATIONS = 10;

    /** 单轮并行池缺省同时在飞上限（ADR-0018；DSH 同款缺省，配置为 1 即完全串行）。 */
    public static final int DEFAULT_MAX_PARALLEL_TOOL_CALLS = 10;

    private final LlmAdapter llm;
    private final ToolsService tools;
    private final Session session;
    private final PromptRegistry prompts;
    private final int maxIterations;
    private final int maxParallelToolCalls;
    /** 上下文治理管线（M9；null = 未装配，投影直通——治理可选零残留）。 */
    private final ContextGovernance governance;
    /** 发起呈现位标记（null = 无呈现位，如子代理内部 agent）：随工具执行携带进管线。 */
    private final String presenterId;
    /** 附件引用的请求变体解析器（M21；null = 视觉未启用，请求中丢弃图片部件）。 */
    private final dev.duo.harness.attachment.RequestVariants requestVariants;
    /** files 投递服务（M21 工单 06；null = inline 投递）。 */
    private final dev.duo.harness.attachment.ImageFileDelivery fileDelivery;
    /** 视觉能力开关（llm.vision，ADR-0022）：true 时引用解析为 base64 图片部件。 */
    private final boolean vision;
    /**
     * 两级收件箱（M23 ADR-0025 决策一；next-step 级由 M19 steer 单级升级）：
     * busy 期间外部线程经 {@link #injectUserMessage}（next-step，step 边界排干）或
     * {@link #injectNextTurn}（next-turn，turn 收口后由呈现位排干生效）投递——并发
     * 队列隔离注入线程与 send 线程，多条照排；收件箱为内存态，消费时才落 user/message。
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> nextStepInbox =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<String> nextTurnInbox =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

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
        this(llm, tools, session, prompts, maxIterations, DEFAULT_MAX_PARALLEL_TOOL_CALLS, governance);
    }

    /**
     * 完整构造：并发度显式版（ADR-0018）。
     *
     * @param maxParallelToolCalls 单轮并行池同时在飞上限（配置为 1 即完全串行，
     *                             兼排障开关——执行回到调用线程，行为与串行时代一致）
     */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session,
                            PromptRegistry prompts, int maxIterations,
                            int maxParallelToolCalls, ContextGovernance governance) {
        this(llm, tools, session, prompts, maxIterations, maxParallelToolCalls, governance, null);
    }

    /**
     * 完整构造（呈现位标记版，M19 亲和路由）：标记随工具执行进管线——审批/提问的
     * ask 请求据此路由给发起呈现位的回答者，hooks 载荷顺带透传。
     *
     * @param presenterId 呈现位标记（如 {@code "cli"} / {@code "web"}；子代理等无呈现位为 null）
     */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session,
                            PromptRegistry prompts, int maxIterations,
                            int maxParallelToolCalls, ContextGovernance governance,
                            String presenterId) {
        this(llm, tools, session, prompts, maxIterations, maxParallelToolCalls,
                governance, presenterId, null, false, null);
    }

    /** 全参构造（M21 工单 05）：requestVariants 非空且 vision=true 时附件引用进请求。 */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session,
                            PromptRegistry prompts, int maxIterations,
                            int maxParallelToolCalls, ContextGovernance governance,
                            String presenterId, dev.duo.harness.attachment.RequestVariants requestVariants,
                            boolean vision, dev.duo.harness.attachment.ImageFileDelivery fileDelivery) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.session = Objects.requireNonNull(session, "session");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations 至少为 1: " + maxIterations);
        }
        if (maxParallelToolCalls < 1) {
            throw new IllegalArgumentException("maxParallelToolCalls 至少为 1: " + maxParallelToolCalls);
        }
        this.maxIterations = maxIterations;
        this.maxParallelToolCalls = maxParallelToolCalls;
        this.governance = governance;
        this.presenterId = presenterId;
        this.requestVariants = requestVariants;
        this.fileDelivery = fileDelivery;
        this.vision = vision;
    }

    @Override
    public AgentReply send(String userText, AgentListener listener) {
        Objects.requireNonNull(listener, "listener");
        session.append(SessionEvent.userMessage(userText));

        List<ToolInvocation> invocations = new ArrayList<>();
        StringBuilder finalReply = new StringBuilder();
        boolean completed = false;

        for (int iteration = 1; iteration <= maxIterations && !completed; iteration++) {
            drainInbox();
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

            // 工具执行桥：tool_calls 按并发安全性分组执行（ADR-0018）——连续并发安全
            // 调用成组进虚拟线程滚动池并行执行，独占调用作为顺序屏障单独执行；
            // 无论完成先后，tool/call 与 tool/result 严格按 model 序成对提交——
            // 事件日志形态与串行执行同构，游标回放 / 尾窗 / 投影零特判。
            // 思考内容随 tool/call 事件持久化——会话投影重建的请求历史天然完整
            // （思考模式 provider 要求历史工具调用消息回传 reasoning，ADR 见 BUG-20260913-03）
            executeToolCalls(turn, listener, invocations);
        }

        if (!completed) {
            String failure = "已达最大迭代轮数（" + maxIterations + "），共执行 "
                    + invocations.size() + " 次工具调用";
            return new AgentReply(failure, invocations, false);
        }
        return new AgentReply(finalReply.toString(), invocations, true);
    }

    /**
     * next-step 级注入（M19 立，M23 ADR-0025 升两级命名）：入收件箱即返回——排干
     * 只在 send 线程的 step 边界发生（会话单写者约定不被破坏）；send 空闲期间投递
     * 的文本由下一次 send 的首个 step 边界排干，不丢。
     */
    @Override
    public boolean injectUserMessage(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        nextStepInbox.add(text);
        return true;
    }

    /** next-turn 级注入（M23 ADR-0025）：只入队不生效——排干交给呈现位收口消费。 */
    @Override
    public boolean injectNextTurn(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        nextTurnInbox.add(text);
        return true;
    }

    /** turn 收口排干：next-turn 队列全部取走（先进先出），二次调用依次取空。 */
    @Override
    public java.util.List<String> drainNextTurn() {
        if (nextTurnInbox.isEmpty()) {
            return List.of();
        }
        List<String> drained = new ArrayList<>();
        String text;
        while ((text = nextTurnInbox.poll()) != null) {
            drained.add(text);
        }
        return List.copyOf(drained);
    }

    /**
     * step 边界排干（next-step 级）：逐条落普通 {@code user/message} 后即进入下一轮
     * 请求构造——不发明 steer 专属事件，多条照排（投影与回放对连续 user/message
     * 天然兼容）；飞行中的工具组不受影响（排干只发生在工具组完整跑完之后）。
     */
    private void drainInbox() {
        String injected;
        while ((injected = nextStepInbox.poll()) != null) {
            session.append(SessionEvent.userMessage(injected));
        }
    }

    /**
     * 单轮 tool_calls 的分组调度（ADR-0018）：按 model 序扫描，连续并发安全调用
     * 成组进池；撞独占调用即屏障——排空当前组、独占调用单独执行、再继续分组。
     */
    private void executeToolCalls(LlmTurn turn, AgentListener listener,
                                  List<ToolInvocation> invocations) {
        List<ToolCallRequest> calls = turn.toolCalls();
        int i = 0;
        while (i < calls.size()) {
            if (isConcurrentSafe(calls.get(i))) {
                int j = i + 1;
                while (j < calls.size() && isConcurrentSafe(calls.get(j))) {
                    j++;
                }
                executeToolGroup(calls.subList(i, j), turn, listener, invocations);
                i = j;
            } else {
                executeOneToolCall(calls.get(i), turn, listener, invocations);
                i++;
            }
        }
    }

    /**
     * 并发安全组的滚动池执行：虚拟线程 fan-out（信号量限在飞上限，完成一个补一个），
     * 提交收敛回本线程按 model 序成对写入——前序就绪即提交，不等全组。
     */
    private void executeToolGroup(List<ToolCallRequest> group, LlmTurn turn,
                                  AgentListener listener, List<ToolInvocation> invocations) {
        if (group.size() == 1 || maxParallelToolCalls <= 1) {
            // 快速路径：单元素组或并发度为 1 时无并行收益，退回调用线程执行——
            // 行为与串行时代一致（含执行线程不变，排障语义忠实）
            for (ToolCallRequest call : group) {
                executeOneToolCall(call, turn, listener, invocations);
            }
            return;
        }
        List<Future<ToolResult>> futures = new ArrayList<>(group.size());
        Semaphore permits = new Semaphore(maxParallelToolCalls);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ToolCallRequest call : group) {
                JsonNode args = argumentsAsJson(call.argumentsJson());
                futures.add(pool.submit(() -> {
                    permits.acquire();
                    try {
                        return tools.execute(call.name(), args, presenterId);
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (int k = 0; k < group.size(); k++) {
                ToolCallRequest call = group.get(k);
                commitToolCall(call, awaitResult(futures.get(k), call), turn, listener, invocations);
            }
        }
    }

    /** 独占调用：当前线程执行 + 成对提交（屏障语义下池已排空，独享执行期）。 */
    private void executeOneToolCall(ToolCallRequest call, LlmTurn turn,
                                    AgentListener listener, List<ToolInvocation> invocations) {
        ToolResult result = tools.execute(call.name(), argumentsAsJson(call.argumentsJson()),
                presenterId);
        commitToolCall(call, result, turn, listener, invocations);
    }

    /** 成对有序提交：tool/call 与 tool/result 相邻落日志 + 回调 + 调用台账（model 序）。 */
    private void commitToolCall(ToolCallRequest call, ToolResult result, LlmTurn turn,
                                AgentListener listener, List<ToolInvocation> invocations) {
        session.append(SessionEvent.toolCall(call.id(), call.name(), call.argumentsJson(),
                turn.reasoningContent()));
        listener.onToolCall(call.name(), call.argumentsJson());
        String resultText = String.valueOf(result.value());
        session.append(SessionEvent.toolResult(call.id(), call.name(), resultText));
        invocations.add(new ToolInvocation(call.name(), call.argumentsJson(),
                resultText, result.isError()));
        listener.onToolResult(call.name(), resultText, result.isError());
    }

    /** 并行组的等待收敛：执行异常已由工具域收敛为 error 结果，这里兜住等待自身的异常。 */
    private ToolResult awaitResult(Future<ToolResult> future, ToolCallRequest call) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("工具执行等待被中断: " + call.name());
        } catch (ExecutionException e) {
            return ToolResult.error("工具执行异常: " + call.name() + " — " + e.getCause());
        }
    }

    /**
     * 并发安全判定（fail-closed，ADR-0018）：未知工具、需审批、判定抛错
     * （含参数非法）、非严格 true 一律独占——独占路径保留既有报错行为。
     */
    private boolean isConcurrentSafe(ToolCallRequest call) {
        ToolDefinition def = tools.list().stream()
                .filter(d -> d.name().equals(call.name()))
                .findFirst()
                .orElse(null);
        if (def == null || def.requiresApproval()) {
            return false;
        }
        try {
            return def.isConcurrencySafe(argumentsAsJson(call.argumentsJson()));
        } catch (Exception e) {
            return false;
        }
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
        return new ChatRequest(prompts.compose(),
                Messages.toChatMessages(projected, requestVariants, vision, fileDelivery), specs);
    }

    /** 参数 JSON 文本 → JsonNode（适配 ToolsService.execute 入参形态）。 */
    private JsonNode argumentsAsJson(String argumentsJson) {
        try {
            return TOOLS_ARGS_MAPPER.readTree(argumentsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("工具参数不是合法 JSON: " + argumentsJson, e);
        }
    }
}
