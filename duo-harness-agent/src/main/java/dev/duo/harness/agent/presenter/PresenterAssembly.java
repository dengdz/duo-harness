package dev.duo.harness.agent.presenter;

import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.ContextGovernance;
import dev.duo.harness.agent.ExitPlanModeTool;
import dev.duo.harness.agent.PromptRegistry;
import dev.duo.harness.agent.internal.ToolCallingAgent;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmAdapters;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.AskUserTool;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolsService;

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

    /** 上下文治理（M9 四件套）：summary 生成复用同一 adapter 的直答形态。 */
    public static ContextGovernance governance(LlmAdapter llm) {
        return new ContextGovernance(llm);
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
