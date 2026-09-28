package dev.duo.harness.agent;

import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.ToolsService;

/**
 * agent 装配参数对象（M28 工单 03，H-09 参数堆消解）：执行链装配的唯一全参形态——
 * 「每加功能 +1 参 +2 重载」的事故根因就此收口；新能力进 {@link AgentCapabilities}，
 * 装配签名不再增长。
 *
 * @param llm                  LLM 流式适配器
 * @param tools                工具域服务（Function Calling 的执行后端）
 * @param session              会话（多轮记忆来源与事件落点）
 * @param prompts              prompt 注册表（每轮组装 system 提示）
 * @param maxIterations        最大循环轮数（防死循环上限，至少为 1）
 * @param maxParallelToolCalls 单轮并行池同时在飞上限（ADR-0018；1 即完全串行，至少为 1）
 * @param presenterId          发起呈现位标记（null = 无呈现位，如子代理内部 agent）
 * @param capabilities         可选能力集（null 规范化为全缺席——缺席语义见其类注释）
 */
public record AgentSpec(
        LlmAdapter llm,
        ToolsService tools,
        Session session,
        dev.duo.harness.agent.prompt.PromptRegistry prompts,
        int maxIterations,
        int maxParallelToolCalls,
        String presenterId,
        AgentCapabilities capabilities) {

    public AgentSpec {
        java.util.Objects.requireNonNull(llm, "llm");
        java.util.Objects.requireNonNull(tools, "tools");
        java.util.Objects.requireNonNull(session, "session");
        java.util.Objects.requireNonNull(prompts, "prompts");
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations 至少为 1: " + maxIterations);
        }
        if (maxParallelToolCalls < 1) {
            throw new IllegalArgumentException("maxParallelToolCalls 至少为 1: " + maxParallelToolCalls);
        }
        capabilities = capabilities == null ? AgentCapabilities.none() : capabilities;
    }
}
