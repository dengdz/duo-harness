package dev.duo.harness.agent.presenter;

import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.governance.ContextGovernance;
import dev.duo.harness.agent.plan.ExitPlanModeTool;
import dev.duo.harness.agent.prompt.PromptRegistry;
import dev.duo.harness.agent.internal.ToolCallingAgent;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmAdapters;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.AskUserTool;
import dev.duo.harness.tools.InteractionService;
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

    /** 对话执行者：工具循环 + prompt 注册表 + 治理，迭代上限取内核缺省。 */
    public static ChatAgent chatAgent(LlmAdapter llm, ToolsService tools, Session session,
                                      PromptRegistry prompts, ContextGovernance governance) {
        return new ToolCallingAgent(llm, tools, session, prompts,
                ToolCallingAgent.MAX_ITERATIONS, governance);
    }

    /**
     * HITL 交互工具注册（查重先到先得）：ask_user 与计划呈交随呈现位装配注册——
     * 任意单呈现位部署下 HITL 完整；多呈现位共存（如 cli + web 双开）时先到方胜出，
     * 不触发内核重复注册拒绝。计划退出的状态清理回调由呈现位给出（Web 无计划指导
     * 片段可清，传空 Runnable）。
     */
    public static void registerInteractionTools(Context ctx, ToolsService tools,
                                                InteractionService answers,
                                                Supplier<Session> currentSession,
                                                Runnable onPlanExited) {
        registerIfAbsent(tools, ctx, "ask_user", () -> new AskUserTool(answers));
        registerIfAbsent(tools, ctx, "exit_plan_mode",
                () -> new ExitPlanModeTool(answers, currentSession, onPlanExited));
    }

    /** 同名已注册则跳过——多呈现位共存时先到方胜出。 */
    private static void registerIfAbsent(ToolsService tools, Context ctx, String name,
                                         Supplier<ToolDefinition> factory) {
        if (tools.list().stream().noneMatch(definition -> name.equals(definition.name()))) {
            tools.register(ctx, factory.get());
        }
    }
}
