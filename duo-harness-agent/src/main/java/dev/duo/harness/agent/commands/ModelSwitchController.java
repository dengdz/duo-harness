package dev.duo.harness.agent.commands;

import dev.duo.harness.session.Session;

/**
 * 模型/思考切换控制器（M38 工单 01，ADR-0040 决策三）：单一呈现位执行链的切换面——
 * 持有本呈现位的 activeConfig 与 SwappableLlmAdapter，提供查看与切换（含白名单
 * 校验、换链、会话事件落盘）。每呈现位一个实例（本呈现位独立裁定：Web 切只影响
 * Web），经 {@link ModelSwitchRegistry} 按呈现位登记，命令 handler 按发起面取用。
 */
public interface ModelSwitchController {

    /** 查看文本（/model 无参）：当前模型与可切清单。 */
    String describeModels();

    /**
     * 切换模型：白名单校验、swap 换链（下一轮生效）、model/intent 事件落会话。
     *
     * @param target 目标模型名（清单外拒切）
     * @param session 发起会话（事件落盘载体）
     * @return 用户面结果文本（成功/已是当前/清单外拒切/清单缺席不可切）
     */
    String switchModel(String target, Session session);

    /** 查看文本（/effort 无参）：当前思考档、四档清单与 provider 映射说明。 */
    String describeEfforts();

    /**
     * 切换思考等级：四档校验、swap 换链（下一轮生效）、model/effort 事件落会话。
     *
     * @param target 目标思考档（off/low/medium/high，非法档拒切）
     * @param session 发起会话（事件落盘载体）
     * @return 用户面结果文本（成功/已是当前/非法档拒切，附 provider 映射说明）
     */
    String switchEffort(String target, Session session);
}
