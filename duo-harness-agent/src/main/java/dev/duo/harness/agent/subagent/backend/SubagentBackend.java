package dev.duo.harness.agent.subagent.backend;

import dev.duo.harness.agent.subagent.SubagentTemplate;
import dev.duo.harness.session.Session;

/**
 * 子代理后端（ADR-0015 决策 1）：子任务的一次性执行通道。本期仅实现"同进程
 * 内嵌 agent"一种（{@link EmbeddedSubagentBackend}）；跨 harness 委派（真实
 * Claude Code / Codex 桥）为远期池——届时新增实现承接本契约，装配处可替换。
 *
 * <p>线程约定：{@link #run} 阻塞至任务结束，调用方决定执行线程（内嵌路径由
 * 管理者转入后台虚拟线程，spawn/fork 工具本身立即返回）。</p>
 */
public interface SubagentBackend {

    /**
     * 执行一次子任务：构造子 agent（模板工具集 + 可选专属提示）并跑完任务循环。
     * 子会话由调用方建好传入——后端只负责"跑"，不负责生命周期与登记。
     *
     * @throws Exception LLM / 工具域异常（调用方收敛为失败回流，不让子任务异常
     *                   击穿后台线程）
     */
    Outcome run(Task task) throws Exception;

    /** 子任务载荷：id 即子会话 id（同一标识贯穿注册表、引用事件与控制面）。 */
    record Task(String agentId, SubagentTemplate template, String description, Session session) {
    }

    /** 子任务结果：completed = agent 循环正常给出最终回答；未完成时 failure 给原因。 */
    record Outcome(String finalAnswer, boolean completed, String failure) {
    }
}
