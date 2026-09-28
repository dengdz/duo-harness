package dev.duo.harness.agent;

/**
 * 记忆注入端口（M28 工单 03，H-04 能力接口化）：agent 循环每轮请求组装时经本端口
 * 取记忆本 meta_user 段——内核循环只认端口不认记忆域具体类，实现方（记忆本服务）
 * 由装配层以方法引用接入；缺席（null）= 零注入。
 */
@FunctionalInterface
public interface MemoryInjector {

    /** 记忆本 meta_user 段（请求视图专用不落会话日志）；null = 本轮无记忆可注入。 */
    String metaUserSection();
}
