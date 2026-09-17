package dev.duo.harness.tools;

import java.util.List;
import java.util.Objects;

/**
 * 交互请求：需要人来作答的一次请求（审批 / 提问 / 计划复核），经交互 seam 交给在场回答者。
 *
 * <p>三类 {@code kind}：</p>
 * <ul>
 *   <li>{@link #KIND_APPROVAL}：harness 发起——工具调用被声明需审批，subject = 工具名，
 *       detail = 参数摘要；</li>
 *   <li>{@link #KIND_QUESTION}：模型发起——ask_user 提问，subject = 问题文本，
 *       options = 可选选项（空 = 自由文本），multiSelect = 是否多选；</li>
 *   <li>{@link #KIND_PLAN}：模型发起——exit_plan_mode 呈交计划复核，subject = 呈交工具名
 *       （审计与卡片渲染的身份键），detail = 计划全文，options = 复核选项。</li>
 * </ul>
 *
 * @param kind        请求类别（KIND_APPROVAL / KIND_QUESTION / KIND_PLAN）
 * @param subject     一行主题（审批 = 工具名；提问 = 问题文本；计划 = 呈交工具名）
 * @param detail      补充信息（审批 = 参数摘要；提问为空串；计划 = 计划全文）
 * @param options     预设选项（提问/计划类专用；空 = 自由文本）
 * @param multiSelect 选项是否多选（提问类专用）
 */
public record InteractionRequest(String kind, String subject, String detail,
                                 List<String> options, boolean multiSelect) {

    /** 审批类请求（harness 发起）。 */
    public static final String KIND_APPROVAL = "approval";

    /** 提问类请求（模型经 ask_user 发起）。 */
    public static final String KIND_QUESTION = "question";

    /** 计划复核类请求（模型经 exit_plan_mode 发起）。 */
    public static final String KIND_PLAN = "plan";

    /** 构造时校验非空与防御性拷贝——错误前移到构造点。 */
    public InteractionRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        detail = detail == null ? "" : detail;
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** 审批请求工厂：subject = 工具名，detail = 参数摘要。 */
    public static InteractionRequest approval(String toolName, String argsSummary) {
        return new InteractionRequest(KIND_APPROVAL, toolName, argsSummary, List.of(), false);
    }

    /** 提问请求工厂：subject = 问题文本，options 空 = 自由文本回答。 */
    public static InteractionRequest question(String question, List<String> options, boolean multiSelect) {
        return new InteractionRequest(KIND_QUESTION, question, "", options, multiSelect);
    }

    /**
     * 计划复核请求工厂（BUG-20260917-04）：subject = 呈交工具名——审计桥据此写留痕、
     * 呈现位据此渲染计划卡；detail = 计划全文。options = 复核选项，**首个必须是批准项**
     * （审计决定 allow/deny 与工具判据都取它）。
     */
    public static InteractionRequest plan(String toolName, String plan, List<String> options) {
        return new InteractionRequest(KIND_PLAN, toolName, plan, options, false);
    }
}
