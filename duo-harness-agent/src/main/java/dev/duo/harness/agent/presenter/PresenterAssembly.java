package dev.duo.harness.agent.presenter;

import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.governance.ContextGovernance;
import dev.duo.harness.agent.plan.ExitPlanModeTool;
import dev.duo.harness.agent.prompt.PromptRegistry;
import dev.duo.harness.agent.internal.ToolCallingAgent;
import dev.duo.harness.agent.subagent.SubagentHost;
import dev.duo.harness.agent.todo.TodoWriteTool;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmAdapters;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.AskUserTool;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.PipelineTimeout;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolsService;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Supplier;

/**
 * 呈现位共享装配器（ADR-0011）：CLI 与 Web 插件的同构执行链装配单点——
 * LLM 配置装载与重试 adapter、上下文治理、ChatAgent 构建、HITL 交互工具
 * 查重注册。呈现位自己负责的仍是：会话策略（续接/新建与独占锁）、呈现件
 * （终端循环 / HTTP 服务）、回答者注册。
 *
 * <p>各工厂无状态，可静态调用；换会话后重建 agent 用 {@link #chatAgent}
 * 传新会话即可（ToolCallingAgent 持有 final 会话引用，不重建即分脑）。</p>
 */
public final class PresenterAssembly {

    private PresenterAssembly() {
    }

    /**
     * LLM 执行链：OpenAI 兼容适配器 + 按配置参数的重试装饰——组装细节在 llm 契约包
     * 工厂（{@link LlmAdapters#openAiCompatWithRetry}），internal 实现不外泄。
     */
    public static LlmAdapter llmAdapter(LlmConfig config) {
        return LlmAdapters.openAiCompatWithRetry(config);
    }

    /** 上下文治理（M9 四件套）：summary 生成复用同一 adapter 的直答形态（缺省阈值）。 */
    public static ContextGovernance governance(LlmAdapter llm) {
        return governance(llm, null);
    }

    /** 上下文治理（生效阈值注入版）：tuning 组件 null 即回退缺省常量（工单 M13-04）。 */
    public static ContextGovernance governance(LlmAdapter llm, ContextGovernance.Tuning tuning) {
        return new ContextGovernance(llm, tuning);
    }

    /**
     * 解析呈现位 config 的治理段（{@code config.governance}，可省）：段缺席或为 null
     * 返回 null（治理器用缺省常量，0.7.0 行为）；段在场则逐字段严格绑定——未知字段名、
     * 类型不符、数值越界（阈值/窗口/折叠数非正、比例出 (0,1]/[0,1) 各自区间）一律
     * 异常点名，配置错误不做静默纠正。
     *
     * <p>字段名与 {@link ContextGovernance.Tuning} 组件一一对应（spillThresholdChars /
     * pruneThresholdChars / compactionThresholdRatio / contextWindowTokens / keepRecentRatio /
     * minRemoteMessages），均可省。</p>
     *
     * @throws PluginException 字段名未知、类型不符或数值越界（异常消息点名具体字段）
     */
    public static ContextGovernance.Tuning parseGovernance(JsonNode config) {
        if (config == null || !config.hasNonNull("governance")) {
            return null;
        }
        JsonNode node = config.get("governance");
        if (!node.isObject()) {
            throw new PluginException("governance 段必须是对象");
        }
        Integer spill = null;
        Integer prune = null;
        Double ratio = null;
        Long window = null;
        Double keep = null;
        Integer minRemote = null;
        var fieldNames = new java.util.LinkedHashSet<String>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        for (String name : fieldNames) {
            JsonNode value = node.get(name);
            switch (name) {
                case "spillThresholdChars" -> spill = intField(name, value);
                case "pruneThresholdChars" -> prune = intField(name, value);
                case "compactionThresholdRatio" -> ratio = doubleField(name, value);
                case "contextWindowTokens" -> window = longField(name, value);
                case "keepRecentRatio" -> keep = doubleField(name, value);
                case "minRemoteMessages" -> minRemote = intField(name, value);
                default -> throw new PluginException("governance 段存在未知字段: " + name);
            }
        }
        requirePositive("spillThresholdChars", spill == null ? null : spill.longValue());
        requirePositive("pruneThresholdChars", prune == null ? null : prune.longValue());
        requirePositive("contextWindowTokens", window);
        requirePositive("minRemoteMessages", minRemote == null ? null : minRemote.longValue());
        if (ratio != null && (ratio <= 0 || ratio > 1)) {
            throw new PluginException("governance.compactionThresholdRatio 须在 (0,1] 区间: " + ratio);
        }
        if (keep != null && (keep < 0 || keep >= 1)) {
            throw new PluginException("governance.keepRecentRatio 须在 [0,1) 区间: " + keep);
        }
        return new ContextGovernance.Tuning(spill, prune, ratio, window, keep, minRemote);
    }

    /** 整数字段严格绑定：非整数值点名拒绝（"5.0"式小数与字符串一律不放行）。 */
    private static int intField(String name, JsonNode value) {
        if (!value.canConvertToInt() || !value.isIntegralNumber()) {
            throw new PluginException("governance." + name + " 必须是整数: " + value);
        }
        return value.asInt();
    }

    private static long longField(String name, JsonNode value) {
        if (!value.canConvertToLong() || !value.isIntegralNumber()) {
            throw new PluginException("governance." + name + " 必须是整数: " + value);
        }
        return value.asLong();
    }

    private static double doubleField(String name, JsonNode value) {
        if (!value.isNumber()) {
            throw new PluginException("governance." + name + " 必须是数值: " + value);
        }
        return value.asDouble();
    }

    private static void requirePositive(String name, Long value) {
        if (value != null && value <= 0) {
            throw new PluginException("governance." + name + " 必须为正: " + value);
        }
    }

    /**
     * 解析呈现位 config 的可选迭代上限（{@code config.maxIterations}）：缺席或 null 返回
     * 内核缺省（{@link ToolCallingAgent#MAX_ITERATIONS}，不配置行为不变）；在场必须是
     * 正整数，非整数/非正一律异常点名——配置错误不做静默纠正。
     *
     * <p>动机（BUG-20260917-03）：计划模式的引导式探索会连读多份文档/技能，真实仓库级
     * 设计任务可超十余轮；缺省 10 在探索型任务上会把循环停在呈交之前。大预算需求经此
     * 字段显式调高，防失控硬停在缺省路径上原样保留。</p>
     *
     * @throws PluginException 值非正整数
     */
    public static int parseMaxIterations(JsonNode config) {
        if (config == null || !config.hasNonNull("maxIterations")) {
            return ToolCallingAgent.MAX_ITERATIONS;
        }
        JsonNode value = config.get("maxIterations");
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new PluginException("maxIterations 必须是整数: " + value);
        }
        int parsed = value.asInt();
        if (parsed < 1) {
            throw new PluginException("maxIterations 必须为正: " + parsed);
        }
        return parsed;
    }

    /** 对话执行者：工具循环 + prompt 注册表 + 治理，迭代上限取内核缺省。 */
    public static ChatAgent chatAgent(LlmAdapter llm, ToolsService tools, Session session,
                                      PromptRegistry prompts, ContextGovernance governance) {
        return chatAgent(llm, tools, session, prompts, ToolCallingAgent.MAX_ITERATIONS, governance);
    }

    /** 对话执行者（迭代上限显式版，BUG-20260917-03）：呈现位经 {@link #parseMaxIterations} 传入。 */
    public static ChatAgent chatAgent(LlmAdapter llm, ToolsService tools, Session session,
                                      PromptRegistry prompts, int maxIterations,
                                      ContextGovernance governance) {
        return new ToolCallingAgent(llm, tools, session, prompts, maxIterations, governance);
    }

    /**
     * 对话执行者（并发度显式 + 呈现位标记版，M19 亲和路由 ADR-0020 决策 7）：
     * 标记随 agent 的工具执行进管线——审批/提问的 ask 请求据此路由给发起呈现位的
     * 回答者（"谁发起谁作答"）。
     */
    public static ChatAgent chatAgent(LlmAdapter llm, ToolsService tools, Session session,
                                      PromptRegistry prompts, int maxIterations,
                                      int maxParallelToolCalls, ContextGovernance governance,
                                      String presenterId) {
        return new ToolCallingAgent(llm, tools, session, prompts, maxIterations,
                maxParallelToolCalls, governance, presenterId);
    }

    /**
     * 解析呈现位 config 的可选并发度（{@code config.maxParallelToolCalls}，ADR-0018）：
     * 缺席或 null 返回内核缺省（{@link ToolCallingAgent#DEFAULT_MAX_PARALLEL_TOOL_CALLS}，
     * 不配置行为照旧——并发即生效）；在场必须是正整数，配置为 1 即完全串行
     * （兼排障开关：怀疑并发引发问题时一键退回串行时代行为）。非整数 / 非正
     * 一律异常点名——配置错误不做静默纠正（与 {@link #parseMaxIterations} 同规）。
     *
     * @throws PluginException 值非正整数
     */
    public static int parseMaxParallelToolCalls(JsonNode config) {
        if (config == null || !config.hasNonNull("maxParallelToolCalls")) {
            return ToolCallingAgent.DEFAULT_MAX_PARALLEL_TOOL_CALLS;
        }
        JsonNode value = config.get("maxParallelToolCalls");
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new PluginException("maxParallelToolCalls 必须是整数: " + value);
        }
        int parsed = value.asInt();
        if (parsed < 1) {
            throw new PluginException("maxParallelToolCalls 必须为正: " + parsed);
        }
        return parsed;
    }

    /**
     * 解析呈现位 config 的可选管线超时（{@code config.pipelineTimeoutMs}，ADR-0018）：
     * 缺席或 null 返回缺省（{@link PipelineTimeout#DEFAULT_TIMEOUT_MS} = 120s）；
     * 在场必须是正整数。非整数 / 非正一律异常点名（与 {@link #parseMaxIterations} 同规）。
     *
     * @throws PluginException 值非正整数
     */
    public static long parsePipelineTimeoutMs(JsonNode config) {
        if (config == null || !config.hasNonNull("pipelineTimeoutMs")) {
            return PipelineTimeout.DEFAULT_TIMEOUT_MS;
        }
        JsonNode value = config.get("pipelineTimeoutMs");
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new PluginException("pipelineTimeoutMs 必须是整数: " + value);
        }
        long parsed = value.asLong();
        if (parsed <= 0) {
            throw new PluginException("pipelineTimeoutMs 必须为正: " + parsed);
        }
        return parsed;
    }

    /**
     * 挂载管线缺省超时（ADR-0018）：tools/execute 段超时监听器随呈现位装配挂上——
     * 挂载点在装配方 Context（插件作用域），本方法是呈现位共享装配的单点封装。
     */
    public static void mountPipelineTimeout(Context ctx, ToolsService tools, long defaultTimeoutMs) {
        PipelineTimeout.mount(ctx, tools, defaultTimeoutMs);
    }

    /**
     * HITL 交互工具注册（查重先到先得）：ask_user 与计划呈交随呈现位装配注册——
     * 任意单呈现位部署下 HITL 完整；多呈现位共存（如 cli + web 双开）时工具实例
     * 先到方胜出、不触发内核重复注册拒绝，**会话供给各记各账**（M19 亲和路由，
     * ADR-0020 决策 7）：后来呈现位把自己的 {@code presenterId → 会话供给} 补记进
     * 既有 exit_plan_mode 实例——批准/打回的 plan/mode 事件写进发起方会话，双开下
     * 计划状态不串位。计划退出的状态清理回调由呈现位给出（Web 无计划指导片段可清，
     * 传空 Runnable）。
     */
    public static void registerInteractionTools(Context ctx, ToolsService tools,
                                                InteractionService answers,
                                                String presenterId,
                                                Supplier<Session> currentSession,
                                                Runnable onPlanExited) {
        tools.list().stream()
                .filter(definition -> "exit_plan_mode".equals(definition.name()))
                .findFirst()
                .ifPresentOrElse(
                        existing -> {
                            if (existing instanceof ExitPlanModeTool tool) {
                                tool.bindSession(presenterId, currentSession);
                            }
                        },
                        () -> tools.register(ctx, new ExitPlanModeTool(answers, presenterId,
                                currentSession, onPlanExited)));
        registerIfAbsent(tools, ctx, "ask_user", () -> new AskUserTool(answers));
    }

    /**
     * todo_write 注册（ADR-0018，查重先到先得）：任务分解抓手随呈现位装配注册，
     * 会话供给与交互工具同模式（换绑后留新会话）。
     */
    public static void registerTodoWriteTool(Context ctx, ToolsService tools,
                                             Supplier<Session> currentSession) {
        registerIfAbsent(tools, ctx, TodoWriteTool.NAME, () -> new TodoWriteTool(currentSession));
    }

    /** 同名已注册则跳过——多呈现位共存时先到方胜出。 */
    private static void registerIfAbsent(ToolsService tools, Context ctx, String name,
                                         Supplier<ToolDefinition> factory) {
        if (tools.list().stream().noneMatch(definition -> name.equals(definition.name()))) {
            tools.register(ctx, factory.get());
        }
    }

    /**
     * subagent 宿主发布（M15，ADR-0015）：呈现位把子代理执行链所需的父侧构件
     * （LLM adapter / 治理阈值 / 当前会话供给）发布为 {@code subagent-host} 服务
     * ——{@code SubagentPlugin} 经 inject 读取它自行装配五件工具。**依赖方向由
     * 呈现位指向 subagent 插件**：呈现位不必知道 subagent 是否存在，未配置模板
     * 的部署零感知（本方法只发布服务，不注册任何工具）。
     *
     * <p>多呈现位共存（如 cli + web 双开）时先到方发布、后来方跳过——与会话锁
     * 归属一致（先启动的呈现位是当前会话的属主，其会话供给才是"当前父会话"的
     * 正解）。</p>
     */
    public static void publishSubagentHost(Context ctx, LlmAdapter llm,
                                           ContextGovernance.Tuning tuning,
                                           Supplier<Session> currentSession) {
        if (ctx.hasService(SubagentHost.SERVICE_NAME)) {
            return; // 先到方胜出（多呈现位共存）
        }
        ctx.provide(SubagentHost.SERVICE_NAME, new SubagentHost(llm, tuning, currentSession));
    }
}
