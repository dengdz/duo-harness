package dev.duo.harness.agent;

import dev.duo.harness.agent.governance.ContextGovernance;
import dev.duo.harness.attachment.ImageFileDelivery;
import dev.duo.harness.attachment.RequestVariants;

/**
 * agent 循环的可选能力集（M28 工单 03，H-04 接口面整理）：治理、视觉、记忆、
 * 计划裁决等「不装配即缺省行为」的能力集中一处——缺席语义在此一处说清
 * （各组件注释），内核循环只按能力探测，不在场零注入零残留。
 *
 * <p>跨模块能力（附件域变体/投递）保留具体类型：其值对象与异常类型同属附件域
 * 词汇，抽端口的样板成本高于收益，模块级解耦归 H-11（M28+ 结构域）。</p>
 *
 * @param governance      上下文治理管线（null = 未装配，投影直通——治理可选零残留）
 * @param requestVariants 附件引用的请求变体解析器（M21；null = 视觉未启用，请求中丢弃图片部件）
 * @param vision          视觉能力开关（llm.vision，ADR-0022）：true 时引用解析为 base64 图片部件
 * @param fileDelivery    files 投递服务（M21 工单 06；null = inline 投递）
 * @param planBashGate    plan 态 bash 只读裁决端口（M24 工单 04；null = plan 态 bash 一律拒）
 * @param memory          记忆注入端口（M25 工单 02；null = 零注入）
 * @param agentsMd        AGENTS.md 注入端口（M25 工单 07；null = 零注入；子代理恒缺席）
 */
public record AgentCapabilities(
        ContextGovernance governance,
        RequestVariants requestVariants,
        boolean vision,
        ImageFileDelivery fileDelivery,
        PlanBashGate planBashGate,
        MemoryInjector memory,
        AgentsMdInjector agentsMd) {

    /** 全缺席能力集：治理直通、视觉关闭、零注入——短构造与纯对话装配的缺省形态。 */
    public static AgentCapabilities none() {
        return new AgentCapabilities(null, null, false, null, null, null, null);
    }

    /** 仅治理能力：短构造带治理管线的形态（治理外能力全缺席）。 */
    public static AgentCapabilities of(ContextGovernance governance) {
        return new AgentCapabilities(governance, null, false, null, null, null, null);
    }
}
