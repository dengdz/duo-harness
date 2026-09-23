package dev.duo.harness.agent.plan;

import dev.duo.harness.agent.todo.TodoWriteTool;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.fs.FsGlobTool;
import dev.duo.harness.tools.fs.FsGrepTool;
import dev.duo.harness.tools.fs.FsReadTool;
import dev.duo.harness.tools.fs.ReadImageTool;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 计划模式（M7，M24 工单 04 硬禁化）：状态存于会话事件流——`plan/mode` 事件
 * （text = entered/exited）的最后一次出现决定当前形态，续接会话即恢复（与 M4
 * 事件溯源同构）。
 *
 * <p>硬禁语义（ADR-0026 决策三，M7#1「引导式不硬禁」销账）：激活时除指导片段外，
 * 非白名单工具定义<b>不注入模型请求</b>（模型不可见），异常路径仍到达时 pre-execute
 * deny 兜底（理由回模型，不留悬置态）；批准闭环经交互 seam：模型调 exit_plan_mode
 * 呈交计划，人批准后写 exited 事件、工具全量恢复。</p>
 */
public final class PlanMode {

    /** 事件类型（text = entered / exited）。 */
    public static final String EVENT_TYPE = "plan/mode";

    /**
     * 计划态白名单（fail-closed：不在表即注入收缩 + 执行兜底 deny）。逐项理由：
     * 只读四件（read/glob/grep/read_image）探索无副作用；web_fetch/web_search
     * 维持只读档 ask 语义不放开（工单字面——注入照常、审批不豁免）；exit_plan_mode
     * 批准闭环恒在；ask_user/todo_write 无副作用的交互/状态件，计划工作流必需。
     * bash 为参数级语义（同一工具名可读可写），定义级无法判定——不注入；异常路径
     * 到达时经 {@code #denyReason} 按只读判定器裁决（判定器缺席 fail-closed 一律拒）。
     */
    public static final Set<String> WHITELIST = Set.of(
            FsReadTool.NAME, FsGlobTool.NAME, FsGrepTool.NAME, ReadImageTool.NAME,
            "web_fetch", "web_search",
            ExitPlanModeTool.NAME, "ask_user", TodoWriteTool.NAME);

    /** bash 工具名（参数级语义：同一工具名可读可写，走判定器裁决）。 */
    private static final String TOOL_BASH = "bash";

    /** bash 参数解析共享 mapper（ObjectMapper 创建重量级——仓库热路径复用惯例）。 */
    private static final ObjectMapper ARGS_MAPPER = new ObjectMapper();

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

    /**
     * 工具定义是否注入模型请求（M24 工单 04）：非 plan 态全量注入；plan 态仅白名单。
     *
     * @param session  会话（plan 态判定来源）
     * @param toolName 工具名
     */
    public static boolean isVisible(Session session, String toolName) {
        return !isActive(session) || WHITELIST.contains(toolName);
    }

    /**
     * 执行兜底裁决（M24 工单 04，ADR-0026 决策三）：plan 态下白名单外工具到达
     * pre-execute 即拒——返回 deny 理由（回模型，走 tool/result 错误形态，无悬置态）；
     * 放行返回 null。非 plan 态恒放行。bash 经只读判定器参数级裁决（判定器 null 时
     * fail-closed 一律拒——无法证明只读即不冒险）。
     *
     * @param bashReadonlyDetector bash 只读判定器（可 null——fs 工具缺席的纯对话装配
     *                             无 bash 可言，此时 plan 态 bash 到达即拒）
     */
    public static String denyReason(Session session, String toolName, String argsJson,
                                    dev.duo.harness.tools.fs.ReadOnlyBashDetector bashReadonlyDetector) {
        if (!isActive(session) || WHITELIST.contains(toolName)) {
            return null;
        }
        if (TOOL_BASH.equals(toolName) && bashReadonlyDetector != null
                && bashReadonlyDetector.isReadOnlyBash(extractCommand(argsJson))) {
            return null; // ADR：只读 bash 策略表判定通过者在白名单（参数级裁决落点）
        }
        return "[plan] 计划模式下工具 " + toolName + " 不可用（仅只读探索、web_fetch/web_search、"
                + "呈交计划等白名单工具可用）。请继续探索/设计并经 exit_plan_mode 呈交计划；"
                + "批准后本工具自动恢复。";
    }

    /** 从 bash 参数 JSON 提取 command 字段（解析失败按空串 → 判定器 fail-closed 拒）。 */
    private static String extractCommand(String argsJson) {
        try {
            return ARGS_MAPPER.readTree(argsJson == null ? "{}" : argsJson)
                    .path("command").asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
