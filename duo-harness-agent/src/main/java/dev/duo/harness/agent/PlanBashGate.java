package dev.duo.harness.agent;

/**
 * plan 态 bash 只读裁决端口（M28 工单 03，H-04 能力接口化）：plan 态到达的 bash
 * 调用经本端口做参数级只读判定——内核循环与计划模式域只认端口不认 fs 域具体类，
 * 实现方（只读判定器）由装配层以方法引用接入；缺席（null）= fail-closed 一律拒。
 */
@FunctionalInterface
public interface PlanBashGate {

    /**
     * 判定一条 bash 命令是否只读（true=只读可免审批放行；false/异常=按非只读，
     * fail-closed——无法证明只读即不冒险）。
     */
    boolean isReadOnlyBash(String command);
}
