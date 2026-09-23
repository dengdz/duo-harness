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

    /**
     * 迭代预算提醒（M23 工单 10，ADR-0025）：剩 2 轮的那次请求组装时以 USER 角色
     * 附加 system-reminder 段——不落会话日志、不改变迭代上限值与达限行为；
     * 剩 2 轮只出现一次（remaining 恰为 2 的那一轮），不重复刷屏。
     */
    private static final String ITERATION_REMINDER =
            "<system-reminder>迭代预算提示：剩余 2 轮（含本轮），请收敛并交付结论（避免被迭代上限截断）。</system-reminder>";

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
    /** plan 态 bash 只读判定器（M24 工单 04；null = plan 态 bash 一律拒）。 */
    private final dev.duo.harness.tools.fs.ReadOnlyBashDetector planBashDetector;
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
                governance, presenterId, null, false, null, null);
    }

    /**
     * 全参构造（M24 工单 04 plan 硬禁版）：planBashDetector 非 null 时 plan 态到达的
     * bash 调用按只读判定器参数级裁决（只读放行/写命令 deny）；null 时 plan 态 bash
     * 到达一律 fail-closed 拒（无法证明只读即不冒险）。
     */
    public ToolCallingAgent(LlmAdapter llm, ToolsService tools, Session session,
                            PromptRegistry prompts, int maxIterations,
                            int maxParallelToolCalls, ContextGovernance governance,
                            String presenterId, dev.duo.harness.attachment.RequestVariants requestVariants,
                            boolean vision, dev.duo.harness.attachment.ImageFileDelivery fileDelivery,
                            dev.duo.harness.tools.fs.ReadOnlyBashDetector planBashDetector) {
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
        this.planBashDetector = planBashDetector;
    }

    /**
     * 协作式中断（M23 工单 02，ADR-0025 决策一）：requestInterrupt 置标志并打断
     * send 线程；send 在迭代边界 / 工具派发点 / LLM 异常三处检查标志，命中即
     * 收口——已流出文本落 {@code assistant/interrupted}、本轮未派发调用补合成
     * 结果（成对无悬空）、返回 interrupted 的 AgentReply。
     */
    private volatile boolean interruptRequested;
    private volatile Thread sendThread;

    @Override
    public AgentReply send(String userText, AgentListener listener) {
        Objects.requireNonNull(listener, "listener");
        session.append(SessionEvent.userMessage(userText));
        // 复位先于线程登记（OCR 修复）：复位与 requestInterrupt 的「置标志后登记检查」
        // 保持同序——两行之间到达的中断请求按「无 send 在飞」拒绝，不落空为新轮误吞
        interruptRequested = false;
        sendThread = Thread.currentThread();

        List<ToolInvocation> invocations = new ArrayList<>();
        StringBuilder finalReply = new StringBuilder();
        boolean completed = false;

        try {
            for (int iteration = 1; iteration <= maxIterations && !completed; iteration++) {
                if (interruptRequested) {
                    clearResidualInterrupt();
                    return interruptedReply(finalReply, invocations);
                }
                drainInbox();
                // 迭代预算感知（M23 工单 10）：remaining 含本轮，恰剩 2 轮的那次
                // 请求附加提醒——模型主动收敛而不是被硬掐
                int remaining = maxIterations - iteration + 1;
                String reminder = remaining == 2 ? ITERATION_REMINDER : null;
                LlmTurn turn;
                try {
                    turn = llm.streamTurn(buildRequest(reminder), text -> {
                        listener.onChunk(text);
                        finalReply.append(text);
                    });
                } catch (RuntimeException e) {
                    // 流式段被中断打断（阻塞 IO 抛出）——标志位下收敛为中断收口
                    if (interruptRequested) {
                        clearResidualInterrupt();
                        return interruptedReply(finalReply, invocations);
                    }
                    throw e;
                }

                if (!turn.hasToolCalls()) {
                    // 流式中途被打断而适配器吞掉中断正常返回的病态场景：标志位下仍按
                    // 中断收口（残留线程标志会炸后续 NIO 落盘，审查修复）
                    if (interruptRequested) {
                        clearResidualInterrupt();
                        return interruptedReply(finalReply, invocations);
                    }
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
                if (interruptRequested) {
                    clearResidualInterrupt();
                    return interruptedReply(finalReply, invocations);
                }
            }
        } finally {
            sendThread = null;
        }

        if (!completed) {
            String failure = "已达最大迭代轮数（" + maxIterations + "），共执行 "
                    + invocations.size() + " 次工具调用";
            return new AgentReply(failure, invocations, false);
        }
        return new AgentReply(finalReply.toString(), invocations, true);
    }

    /**
     * 中断收口：已流出文本落 {@code assistant/interrupted}（无内容也落标记点），
     * reply 携带 interrupted 标志——会话停在可恢复态，下一次 send 即续接。
     */
    private AgentReply interruptedReply(StringBuilder finalReply, List<ToolInvocation> invocations) {
        session.append(SessionEvent.assistantInterrupted(finalReply.toString()));
        return new AgentReply("已中断（协作式暂停，已流出内容已保留）", invocations, false, true);
    }

    /** 协作式中断请求：有 send 在飞时置标志 + 打断线程；空闲返回 false。 */
    @Override
    public boolean requestInterrupt() {
        Thread active = sendThread;
        if (active == null) {
            return false;
        }
        interruptRequested = true;
        active.interrupt();
        return true;
    }

    /**
     * 清线程残留中断标志（M23 工单 02）：interrupt 的使命在唤醒点（工具 waitFor /
     * 流式阻塞读抛异常）即完成，判定统一走 {@link #interruptRequested} 布尔——
     * 带标志线程上做 NIO FileChannel 写会抛 ClosedByInterruptException，会话
     * 落盘与后续 IO 都会被残留标志炸掉。
     */
    private void clearResidualInterrupt() {
        if (interruptRequested) {
            Thread.interrupted();
        }
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
            if (interruptRequested) {
                // 中断后未派发的调用补合成结果（成对无悬空，M23 工单 02）——
                // 日志忠实表达"模型要求了这些调用、中断使之未执行"，续接时可见
                for (int rest = i; rest < calls.size(); rest++) {
                    ToolCallRequest call = calls.get(rest);
                    commitToolCall(call, ToolResult.error(
                            "[interrupted] 用户中断，本次调用未执行"), turn, listener, invocations);
                }
                return;
            }
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
                futures.add(pool.submit(() -> {
                    permits.acquire();
                    try {
                        return planDenyOrExecute(call);
                    } finally {
                        permits.release();
                    }
                }));
            }
            boolean interruptSeenInGroup = false;
            for (int k = 0; k < group.size(); k++) {
                ToolCallRequest call = group.get(k);
                ToolResult result;
                if (interruptSeenInGroup) {
                    // 中断后组内剩余调用：取消池任务（打断飞行中的执行）+ 合成结果——
                    // 不等各自自然超时（协作式中断的"当前工具终止"语义，M23 工单 02）
                    futures.get(k).cancel(true);
                    result = ToolResult.error("[interrupted] 用户中断，本次调用未完成");
                } else {
                    result = awaitResult(futures.get(k), call);
                    if (interruptRequested) {
                        // 等待方被打断 ≠ 池线程被打断：触发中断的这枚也取消，
                        // 防 pool.close() 傻等其自然超时（M23 工单 02）
                        futures.get(k).cancel(true);
                        interruptSeenInGroup = true;
                    }
                }
                commitToolCall(call, result, turn, listener, invocations);
            }
        }
    }

    /** 独占调用：当前线程执行 + 成对提交（屏障语义下池已排空，独享执行期）。 */
    private void executeOneToolCall(ToolCallRequest call, LlmTurn turn,
                                    AgentListener listener, List<ToolInvocation> invocations) {
        ToolResult result = planDenyOrExecute(call);
        commitToolCall(call, result, turn, listener, invocations);
    }

    /**
     * 执行入口（M24 工单 04 pre-execute 兜底）：plan 态白名单外工具到达即拒——
     * 理由经 tool/result 错误形态回模型（成对落日志无悬置态），不触达工具实现。
     * 非 plan 态直通（与既有行为一致）。
     */
    private ToolResult planDenyOrExecute(ToolCallRequest call) {
        String denyReason = dev.duo.harness.agent.plan.PlanMode.denyReason(
                session, call.name(), call.argumentsJson(), planBashDetector);
        if (denyReason != null) {
            return ToolResult.error(denyReason);
        }
        return tools.execute(call.name(), argumentsAsJson(call.argumentsJson()), presenterId);
    }

    /** 成对有序提交：tool/call 与 tool/result 相邻落日志 + 回调 + 调用台账（model 序）。 */
    private void commitToolCall(ToolCallRequest call, ToolResult result, LlmTurn turn,
                                AgentListener listener, List<ToolInvocation> invocations) {
        // 中断唤醒已完成（工具异常已收敛）：清线程残留标志，防 NIO FileChannel 写在
        // 带标志线程上抛 ClosedByInterruptException 而杀死收口（后续判定走布尔）
        clearResidualInterrupt();
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

    /**
     * 组装本轮请求；{@code reminder} 非空时在消息尾部附加提醒。追加位于治理投影
     * **之后**——治理 compaction 若吞掉提醒会使功能失效，治理后追加是必达的
     * 唯一位置（预算影响约 40 token，次轮 provider 实测用量自然吸收）。
     * 提醒仅进请求视图，不落会话日志。
     */
    private ChatRequest buildRequest(String reminder) {
        // plan 态注入收缩（M24 工单 04，ADR-0026 决策三）：非白名单工具定义不进请求——
        // 模型不可见是第一道防线，pre-execute deny（见执行点）只是异常路径兜底
        boolean planActive = dev.duo.harness.agent.plan.PlanMode.isActive(session);
        List<ToolSpec> specs = tools.list().stream()
                .filter(def -> !planActive
                        || dev.duo.harness.agent.plan.PlanMode.WHITELIST.contains(def.name()))
                .map(def -> new ToolSpec(def.name(), def.description(),
                        def.parameters() == null ? "{}" : def.parameters().toString()))
                .toList();
        List<Message> projected = session.deriveMessages();
        if (governance != null) {
            projected = governance.govern(projected, session);
        }
        List<ChatMessage> chatMessages = new ArrayList<>(
                Messages.toChatMessages(projected, requestVariants, vision, fileDelivery));
        if (reminder != null && !reminder.isBlank()) {
            chatMessages.add(ChatMessage.user(reminder));
        }
        return new ChatRequest(prompts.compose(), chatMessages, specs);
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
