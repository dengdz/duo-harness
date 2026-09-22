package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
 * @param id          请求身份（卡片回填/审计关联键；构造时缺省自动生成 UUID）
 * @param kind        请求类别（KIND_APPROVAL / KIND_QUESTION / KIND_PLAN）
 * @param subject     一行主题（审批 = 工具名；提问 = 问题文本；计划 = 呈交工具名）
 * @param detail      补充信息（审批 = 参数摘要；提问为空串；计划 = 计划全文）
 * @param options     预设选项（提问/计划类专用；空 = 自由文本）
 * @param multiSelect 选项是否多选（提问类专用）
 * @param presenterId 发起呈现位标记（M19 亲和路由，ADR-0020 决策 7；agent 循环执行的
 *                    请求携带，其余为 null）——回答者路由据此发起方优先
 * @param args        结构化调用参数（审批类专用，M24 工单 02：高危判定与规则生成的
 *                    可靠来源；detail 是截断摘要不可解析。其余类别为 NullNode）
 */
public record InteractionRequest(String id, String kind, String subject, String detail,
                                 List<String> options, boolean multiSelect, String presenterId,
                                 JsonNode args) {

    /** 审批类请求（harness 发起）。 */
    public static final String KIND_APPROVAL = "approval";

    /** 提问类请求（模型经 ask_user 发起）。 */
    public static final String KIND_QUESTION = "question";

    /** 计划复核类请求（模型经 exit_plan_mode 发起）。 */
    public static final String KIND_PLAN = "plan";

    /** 兼容构造：无发起呈现位标记（直调与既有调用方）。 */
    public InteractionRequest(String kind, String subject, String detail,
                              List<String> options, boolean multiSelect) {
        this(null, kind, subject, detail, options, multiSelect, null, null);
    }

    /** 兼容构造：携发起呈现位标记。 */
    public InteractionRequest(String kind, String subject, String detail,
                              List<String> options, boolean multiSelect, String presenterId) {
        this(null, kind, subject, detail, options, multiSelect, presenterId, null);
    }

    /** 构造时校验非空与防御性拷贝——错误前移到构造点；id 缺省自动生成（卡片回填关联键）。 */
    public InteractionRequest {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        detail = detail == null ? "" : detail;
        options = options == null ? List.of() : List.copyOf(options);
        presenterId = presenterId == null || presenterId.isBlank() ? null : presenterId;
        args = args == null ? NullNode.getInstance() : args;
    }

    /** 审批请求工厂：subject = 工具名，detail = 参数摘要。 */
    public static InteractionRequest approval(String toolName, String argsSummary) {
        return new InteractionRequest(null, KIND_APPROVAL, toolName, argsSummary, List.of(), false, null, null);
    }

    /** 审批请求工厂（携发起呈现位）：agent 循环的审批管线传发起方标记。 */
    public static InteractionRequest approval(String toolName, String argsSummary, String presenterId) {
        return new InteractionRequest(null, KIND_APPROVAL, toolName, argsSummary, List.of(), false, presenterId, null);
    }

    /** 审批请求工厂（携结构化参数，M24 工单 02）：args 供回答者做高危判定与规则生成。 */
    public static InteractionRequest approval(String toolName, JsonNode args,
                                              String argsSummary, String presenterId) {
        return new InteractionRequest(null, KIND_APPROVAL, toolName, argsSummary,
                List.of(), false, presenterId, args);
    }

    /** 提问请求工厂：subject = 问题文本，options 空 = 自由文本回答。 */
    public static InteractionRequest question(String question, List<String> options, boolean multiSelect) {
        return new InteractionRequest(null, KIND_QUESTION, question, "", options, multiSelect, null, null);
    }

    /** 提问请求工厂（携发起呈现位）：ask_user 本体从执行载荷取发起方标记。 */
    public static InteractionRequest question(String question, List<String> options,
                                              boolean multiSelect, String presenterId) {
        return new InteractionRequest(null, KIND_QUESTION, question, "", options, multiSelect, presenterId, null);
    }

    /**
     * 计划复核请求工厂（BUG-20260917-04）：subject = 呈交工具名——审计桥据此写留痕、
     * 呈现位据此渲染计划卡；detail = 计划全文。options = 复核选项，**首个必须是批准项**
     * （审计决定 allow/deny 与工具判据都取它）。
     */
    public static InteractionRequest plan(String toolName, String plan, List<String> options) {
        return new InteractionRequest(null, KIND_PLAN, toolName, plan, options, false, null, null);
    }

    /** 计划复核请求工厂（携发起呈现位）：计划呈交的会话供给与卡片路由按发起方亲和。 */
    public static InteractionRequest plan(String toolName, String plan, List<String> options,
                                          String presenterId) {
        return new InteractionRequest(null, KIND_PLAN, toolName, plan, options, false, presenterId, null);
    }
}
