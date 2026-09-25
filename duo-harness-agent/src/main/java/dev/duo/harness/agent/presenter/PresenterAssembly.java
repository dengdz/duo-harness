package dev.duo.harness.agent.presenter;

import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.commands.CommandDefinition;
import dev.duo.harness.agent.commands.CommandScope;
import dev.duo.harness.agent.commands.CommandsRegistry;
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
import dev.duo.harness.tools.fs.WorkspacePolicy;
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

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(PresenterAssembly.class);

    private PresenterAssembly() {
    }

    /**
     * LLM 执行链：按 provider 声明选型适配器（anthropic → Anthropic-messages，
     * 其余走 OpenAI 兼容面）+ 按配置参数的重试装饰——组装细节在 llm 契约包
     * 工厂（{@link LlmAdapters#withRetry}），internal 实现不外泄。
     */
    public static LlmAdapter llmAdapter(LlmConfig config) {
        return LlmAdapters.withRetry(config);
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
        return chatAgent(llm, tools, session, prompts, maxIterations, maxParallelToolCalls,
                governance, presenterId, null, false);
    }

    /**
     * 对话执行者（呈现位标记 + 迭代上限显式、并发取内核缺省）：不关心并发调参的
     * 呈现位用本重载——internal 缺省常量不外泄，呈现位经公开重载即可获得缺省并发。
     */
    public static ChatAgent chatAgent(LlmAdapter llm, ToolsService tools, Session session,
                                      PromptRegistry prompts, int maxIterations,
                                      ContextGovernance governance, String presenterId) {
        return chatAgent(llm, tools, session, prompts, maxIterations,
                ToolCallingAgent.DEFAULT_MAX_PARALLEL_TOOL_CALLS, governance, presenterId);
    }

    /**
     * 对话执行者（M25 工单 02 记忆注入版，headless 单次任务形态）：{@code memory}
     * 非 null 时每轮请求把记忆本内容以 user 角色置于消息序列最前；null = 未装配，
     * 零注入。
     */
    public static ChatAgent chatAgent(LlmAdapter llm, ToolsService tools, Session session,
                                      PromptRegistry prompts, int maxIterations,
                                      ContextGovernance governance, String presenterId,
                                      dev.duo.harness.agent.memory.MemoryBook memory) {
        return chatAgent(llm, tools, session, prompts, maxIterations,
                ToolCallingAgent.DEFAULT_MAX_PARALLEL_TOOL_CALLS, governance, presenterId,
                null, false, null, null, memory);
    }

    /**
     * 对话执行者（M21 工单 05 视觉版，ADR-0022）：{@code variants} 非空且
     * {@code vision=true} 时，消息附件引用解析为请求变体并以 base64 图片部件进请求。
     */
    public static ChatAgent chatAgent(LlmAdapter llm, ToolsService tools, Session session,
                                      PromptRegistry prompts, int maxIterations,
                                      int maxParallelToolCalls, ContextGovernance governance,
                                      String presenterId,
                                      dev.duo.harness.attachment.RequestVariants variants,
                                      boolean vision) {
        return chatAgent(llm, tools, session, prompts, maxIterations, maxParallelToolCalls,
                governance, presenterId, variants, vision, null);
    }

    /**
     * 对话执行者（M21 工单 06 files 投递版）：{@code fileDelivery} 非空时图片变体
     * 上传 Files API 换 file_id 进请求（上传失败自动回退 inline base64）。
     */
    public static ChatAgent chatAgent(LlmAdapter llm, ToolsService tools, Session session,
                                      PromptRegistry prompts, int maxIterations,
                                      int maxParallelToolCalls, ContextGovernance governance,
                                      String presenterId,
                                      dev.duo.harness.attachment.RequestVariants variants,
                                      boolean vision,
                                      dev.duo.harness.attachment.ImageFileDelivery fileDelivery) {
        return chatAgent(llm, tools, session, prompts, maxIterations, maxParallelToolCalls,
                governance, presenterId, variants, vision, fileDelivery, null);
    }

    /**
     * 对话执行者（M24 工单 04 plan 硬禁版）：{@code planBashDetector} 非 null 时
     * plan 态到达的 bash 经只读判定器参数级裁决（只读放行/写命令 deny）；null 时
     * plan 态 bash 一律 fail-closed 拒。注入收缩与执行兜底均在 agent 内生效。
     */
    public static ChatAgent chatAgent(LlmAdapter llm, ToolsService tools, Session session,
                                      PromptRegistry prompts, int maxIterations,
                                      int maxParallelToolCalls, ContextGovernance governance,
                                      String presenterId,
                                      dev.duo.harness.attachment.RequestVariants variants,
                                      boolean vision,
                                      dev.duo.harness.attachment.ImageFileDelivery fileDelivery,
                                      dev.duo.harness.tools.fs.ReadOnlyBashDetector planBashDetector) {
        return chatAgent(llm, tools, session, prompts, maxIterations, maxParallelToolCalls,
                governance, presenterId, variants, vision, fileDelivery, planBashDetector, null);
    }

    /**
     * 对话执行者（M25 工单 02 记忆注入版）：{@code memory} 非 null 时每轮请求把
     * 记忆本内容以 user 角色置于消息序列最前（meta_user 通道，请求视图专用）；
     * null = 未装配，零注入。
     */
    public static ChatAgent chatAgent(LlmAdapter llm, ToolsService tools, Session session,
                                      PromptRegistry prompts, int maxIterations,
                                      int maxParallelToolCalls, ContextGovernance governance,
                                      String presenterId,
                                      dev.duo.harness.attachment.RequestVariants variants,
                                      boolean vision,
                                      dev.duo.harness.attachment.ImageFileDelivery fileDelivery,
                                      dev.duo.harness.tools.fs.ReadOnlyBashDetector planBashDetector,
                                      dev.duo.harness.agent.memory.MemoryBook memory) {
        return new ToolCallingAgent(llm, tools, session, prompts, maxIterations,
                maxParallelToolCalls, governance, presenterId, variants, vision, fileDelivery,
                planBashDetector, memory);
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
                                tool.bindSession(presenterId, currentSession, onPlanExited);
                            } else {
                                LOG.warn("exit_plan_mode 已被非本库实现占用（{}），呈现位 [{}] 的"
                                        + "会话供给与批准回调未记账——计划状态可能串位",
                                        existing.getClass().getName(), presenterId);
                            }
                        },
                        () -> tools.register(ctx, new ExitPlanModeTool(answers, presenterId,
                                currentSession, onPlanExited)));
        registerIfAbsent(tools, ctx, "ask_user", () -> new AskUserTool(answers));
    }

    /**
     * 权限档恢复（M19，ADR-0020 决策 10）：呈现位打开/换绑会话后调用——读会话
     * {@code permission/mode} 投影写回全局 workspace 档位（latest-wins，重开恢复最后
     * 切定档）。workspace 服务缺席（纯对话装配）零感跳过。双开语义：档位是全局治理态，
     * 后恢复者生效（与单例 volatile 模型一致）。恢复事件不落盘（读侧恢复非治理动作）。
     *
     * <p>BUG-20260919-03（验收实测）：双开重启时 Web 先恢复切定档、CLI 占用被迫改开
     * 新会话——若"无切档记录即重置缺省"对启动路径也生效，CLI 会把刚恢复的档位覆盖
     * 回缺省。故重置语义只对**显式换绑**（/new、页面新话题/切换——用户主动开新话题，
     * "切档不跨会话惊吓"）生效；**启动续接**（含占用被迫改开）只恢复、不重置——用户
     * 没有开新话题的动作意图，治理态延续。</p>
     *
     * @param resetToInitialIfAbsent true = 无切档记录时重置回装配档（显式换绑场景）；
     *                               false = 无记录保持现状（启动续接场景）
     */
    public static void restorePermissionMode(Context ctx, Session session,
                                             boolean resetToInitialIfAbsent) {
        WorkspacePolicy workspace;
        try {
            workspace = ctx.hasService(WorkspacePolicy.SERVICE_NAME)
                    ? ctx.as(WorkspaceView.class).workspace() : null;
        } catch (Exception e) {
            LOG.warn("权限档恢复跳过：workspace 服务解析失败（视为缺席）", e);
            return; // 服务解析失败等同缺席——恢复是尽力而为的还账，不阻断呈现位启动
        }
        if (workspace == null) {
            return;
        }
        String saved = session.permissionMode();
        if (saved != null) {
            WorkspacePolicy.Mode target;
            try {
                target = WorkspacePolicy.Mode.parse(saved);
            } catch (IllegalArgumentException e) {
                // 会话事件文本非法（手改/向前兼容）：与占用继承路径同口径——回退缺省不留坏档
                LOG.warn("权限档恢复跳过：会话记录档位非法 [{}]，保持当前档", saved);
                return;
            }
            if (target != workspace.mode()) {
                workspace.setMode(target);
            }
        } else if (resetToInitialIfAbsent && workspace.mode() != workspace.initialMode()) {
            workspace.setMode(workspace.initialMode());
        }
    }

    /**
     * 会话级权限规则恢复（M24，ADR-0026 决策一）：读会话 {@code permission/rules}
     * 投影写回全局规则服务（latest-wins，变更后全量快照）。规则随会话生命周期——
     * 续接恢复该会话规则、换绑新会话投影为空即清空，无记录语义一致、不区分
     * 续接/换绑。服务缺席（未挂 permission-rules 插件）零感跳过；坏 JSON 按
     * 空规则处理（解析侧记 warn）。
     */
    public static void restorePermissionRules(Context ctx, Session session) {
        dev.duo.harness.tools.fs.PermissionRules rules;
        try {
            rules = ctx.hasService(dev.duo.harness.tools.fs.PermissionRules.SERVICE_NAME)
                    ? ctx.as(PermissionRulesView.class).permissionRules() : null;
        } catch (Exception e) {
            LOG.warn("会话级权限规则恢复跳过：服务解析失败（视为缺席）", e);
            return;
        }
        if (rules == null) {
            return;
        }
        rules.setSessionRules(dev.duo.harness.tools.fs.PermissionRules.parseRulesJson(
                session.permissionRules(), dev.duo.harness.tools.fs.PermissionRules.Scope.SESSION));
    }

    /** 权限规则服务视图（方法名即服务名）。 */
    interface PermissionRulesView {

        dev.duo.harness.tools.fs.PermissionRules permissionRules();
    }

    /**
     * /title 注册（M19，ADR-0020 决策 11，查重先到先得）：改名命令——双面 ANY +
     * busySafe=true（纯事件写）；再 append {@code session/title} 即改名（latest-wins
     * 投影现成，侧栏即时生效）。标题随对话自动演进不做（一次生成 + 可改名已覆盖）。
     */
    public static void registerTitleCommand(Context ctx, CommandsRegistry commands) {
        if (commands.find("title") == null) {
            commands.register(ctx, new CommandDefinition("title",
                    "改会话标题：/title 新标题（侧栏与标签页即时生效）",
                    CommandScope.ANY, true, context -> {
                    if (context.args().isEmpty()) {
                        return "用法：/title 新标题";
                    }
                    context.session().append(dev.duo.harness.session.SessionEvent.title(context.args()));
                    return "已改名: " + context.args();
                }));
        }
    }

    /**
     * /compact 注册（M19，ADR-0020 决策 6，查重先到先得）：手动压缩命令——双面 ANY
     * （动上下文必须 idle，busySafe=false）；handler 的会话经 {@code CommandContext#session()}
     * 取**发起方**当前会话（双开下各压各的，零串位），治理实例先到方胜出（等价配置，
     * 压缩效果一致）。命令未注册时注册，已注册（另一呈现位先到）跳过。
     */
    public static void registerCompactCommand(Context ctx, CommandsRegistry commands,
                                              ContextGovernance governance) {
        if (commands.find("compact") == null) {
            commands.register(ctx, new CommandDefinition("compact",
                    "手动压缩上下文：远端历史折叠为摘要（会话日志留压缩点，后续请求按其拼接）",
                    CommandScope.ANY, false,
                    context -> governance.compactNow(context.session())));
        }
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
     * /export 导出命令（M21 工单 09，ADR-0022 决策 9）：双面、busySafe（纯读渲染，
     * agent 执行中可导）、command/run+done 审计随分发白得。按发起面分流：CLI 写盘
     * 目录并回显落盘路径；Web 返回下载端点相对 URL（前端拦截自动触发下载流）。
     * 命令未注册时注册，已注册（另一呈现位先到）跳过。
     */
    public static void registerExportCommand(Context ctx, CommandsRegistry commands) {
        registerExportCommand(ctx, commands, java.nio.file.Paths.get("").toAbsolutePath());
    }

    /** 重载（测试注入导出目录；生产 cwd 语义见上）。 */
    static void registerExportCommand(Context ctx, CommandsRegistry commands,
                                      java.nio.file.Path exportDir) {
        if (commands.find("export") != null) {
            return;
        }
        commands.register(ctx, new CommandDefinition("export",
                "导出当前会话：/export [markdown|json]，缺省 markdown（人读记录）；"
                        + "json 为会话日志原样副本。CLI 写盘当前目录，Web 自动下载",
                CommandScope.ANY, true,
                context -> {
                    dev.duo.harness.session.SessionExport.Format format =
                            dev.duo.harness.session.SessionExport.Format.parse(context.args());
                    Session session = context.session();
                    if (format == null) {
                        return "[/export 错误] 未知格式: \"" + context.args()
                                + "\"（可选 markdown | json，缺省 markdown）";
                    }
                    String fileName = dev.duo.harness.session.SessionExport
                            .fileName(session.id(), format);
                    if (context.presenter() == CommandScope.WEB) {
                        return "/api/session/export?format=" + format.argName;
                    }
                    java.nio.file.Path target = exportDir.resolve(fileName);
                    try {
                        java.nio.file.Files.writeString(target,
                                format == dev.duo.harness.session.SessionExport.Format.MARKDOWN
                                        ? dev.duo.harness.session.SessionExport.markdown(session)
                                        : dev.duo.harness.session.SessionExport.jsonl(session),
                                java.nio.charset.StandardCharsets.UTF_8);
                    } catch (java.io.IOException e) {
                        // fail-loud：写失败向上抛（分发器收敛为错误文本），不留半截文件承诺
                        throw new IllegalStateException("导出写盘失败: " + target, e);
                    }
                    return "已导出: " + target.toAbsolutePath();
                }));
    }

    /**
     * @file 提及指南注入（M21 工单 07，ADR-0022 决策 7）：**仅 read 工具在册时**
     * 注册进 prompt 注册表——指南约束"要内容调 read；未 read 不得声称已看过"，
     * 无 read 的部署（纯对话/自定义工具族）注入了也无法兑现，零注入。双呈现位
     * 去重按片段来源先到先得（{@link PromptRegistry#hasSource}）——Web 与 CLI 各自
     * apply 都调用本方法，同源片段只注一份。
     */
    public static void registerFileMentionGuide(Context ctx, ToolsService tools,
                                                PromptRegistry prompts) {
        boolean readPresent = tools.list().stream()
                .anyMatch(definition -> "read".equals(definition.name()));
        if (!readPresent || prompts.hasSource(FILE_MENTION_GUIDE_SOURCE)) {
            return;
        }
        prompts.register(ctx, new dev.duo.harness.agent.prompt.PromptFragment(
                FILE_MENTION_GUIDE_SOURCE, FILE_MENTION_GUIDE));
    }

    /**
     * read_image 视觉闸门回填（M21 收口修正）：fs 插件注册 read_image 时视觉闸门
     * 缺省 false（apply 早于呈现位加载 llm 配置，真值不可得）——呈现位装配后按
     * {@code llm.vision} 回填。双呈现位幂等（同一工具实例重复回填无副作用）。
     */
    public static void wireReadImageVisionGate(ToolsService tools, boolean vision) {
        tools.list().stream()
                .filter(definition -> "read_image".equals(definition.name()))
                .findFirst()
                .ifPresent(definition -> {
                    if (definition instanceof dev.duo.harness.tools.fs.ReadImageTool tool) {
                        tool.setVisionGate(() -> vision);
                    } else {
                        LOG.warn("read_image 已被非本库实现占用（{}），视觉闸门未回填",
                                definition.getClass().getName());
                    }
                });
    }

    /** @file 指南片段来源标识（审计与双呈现位查重键）。 */
    public static final String FILE_MENTION_GUIDE_SOURCE = "file-mention-guide";

    /** @file 指南片段正文（零内容注入——文件内容永远经 read 工具，ADR-0022 决策 7）。 */
    public static final String FILE_MENTION_GUIDE =
            "消息中的 @路径 是工作区文件/目录的引用（如 @src/Main.java、@docs/、@\"含 空格 的名\"），"
                    + "不是已读入的内容。要基于某个文件回答，必须先用 read 工具读取它——"
                    + "未被 read 过的文件不得声称已看过其内容。";

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
    /** workspace 服务的视图接口（方法名即服务名 "workspace"）。 */
    interface WorkspaceView {

        WorkspacePolicy workspace();
    }
}
