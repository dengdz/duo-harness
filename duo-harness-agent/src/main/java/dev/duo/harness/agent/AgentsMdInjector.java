package dev.duo.harness.agent;

/**
 * AGENTS.md 注入端口（M28 工单 03，H-04 能力接口化）：agent 循环每轮请求组装时经
 * 本端口取 AGENTS.md 链 meta_user 段——内核循环只认端口不认提示域具体类，实现方
 * （链服务）由装配层以方法引用接入；缺席（null）= 零注入。
 */
@FunctionalInterface
public interface AgentsMdInjector {

    /** AGENTS.md 链段（项目约定在前、先于记忆段盖顶）；null = 本轮无链可注入。 */
    String section();
}
