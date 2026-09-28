package dev.duo.harness.agent.plan;

import dev.duo.harness.session.Session;

import java.util.function.Supplier;

/**
 * 计划会话绑定能力（M28 工单 03，H-01 类型下探消解）：呈现位装配把
 * {@code presenterId → 会话供给} 记账给 plan 态工具时按本接口探测——工具换实现或
 * 第三方替换只要实现本接口即被正确记账，装配层不再下探具体工具类。
 */
public interface PlanSessionBinder {

    /**
     * 补记呈现位的会话供给与计划呈交回调（双开下计划状态不串位，ADR-0020 决策 7）。
     *
     * @param presenterId      呈现位标记（批准/打回的 plan/mode 事件写进发起方会话）
     * @param session          当前会话供给
     * @param approvedCallback 计划批准回调（计划退出的状态清理；无可清理传空 Runnable）
     */
    void bindSession(String presenterId, Supplier<Session> session, Runnable approvedCallback);
}
