package dev.duo.harness.agent;

import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;

/**
 * 计划模式（M7）：状态存于会话事件流——`plan/mode` 事件（text = entered/exited）
 * 的最后一次出现决定当前形态，续接会话即恢复（与 M4 事件溯源同构）。
 *
 * <p>引导式语义：激活时经 prompt 注册表挂"计划指导片段"约束行为（先探索再设计、
 * 不做修改性操作），不硬禁工具——硬禁需权限预设（M9+），当前防线是写操作的
 * 交互审批（M6）。批准闭环经交互 seam：模型调 exit_plan_mode 呈交计划，
 * 人批准后写 exited 事件并开始执行。</p>
 */
public final class PlanMode {

    /** 事件类型（text = entered / exited）。 */
    public static final String EVENT_TYPE = "plan/mode";

    /** 计划指导片段正文（激活时挂 prompt 注册表，退出即摘除）。 */
    public static final String GUIDANCE =
            "当前处于计划模式：先探索（读文件、向用户提问）再设计方案，"
                    + "不要执行写文件等修改性操作；设计完成后调用 exit_plan_mode 工具呈交完整计划，"
                    + "等待用户批准。";

    private PlanMode() {
    }

    /** entered 事件。 */
    public static SessionEvent enteredEvent() {
        return new SessionEvent(EVENT_TYPE, System.currentTimeMillis(), "entered");
    }

    /** exited 事件。 */
    public static SessionEvent exitedEvent() {
        return new SessionEvent(EVENT_TYPE, System.currentTimeMillis(), "exited");
    }

    /** 当前是否处于计划模式（事件流中最后一次 plan/mode 事件为 entered）。 */
    public static boolean isActive(Session session) {
        boolean active = false;
        for (SessionEvent event : session.events()) {
            if (EVENT_TYPE.equals(event.type())) {
                active = event.text().startsWith("entered");
            }
        }
        return active;
    }
}
