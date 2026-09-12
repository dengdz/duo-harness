package dev.duo.harness.tools.internal;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.tools.AnswersView;
import dev.duo.harness.tools.ApprovalDecision;
import dev.duo.harness.tools.ApprovalPolicyService;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;
import dev.duo.harness.tools.InteractionService;

/**
 * 交互审批策略：把 ask 裁决委托给交互 seam（ADR-0008）——由在场回答者作答。
 *
 * <p>交互服务经 Context 惰性解析（首次裁决时寻址，避免装配顺序耦合）；
 * 服务缺位或无回答者一律 fail-closed 拒绝——交互缺失永不等于默许。
 * 决策署名恒为本策略来源 {@code interactive}，回答者来源呈现在拒绝理由中
 * （放行时的回答者来源由审计层经交互服务侧记录）。</p>
 */
public final class InteractivePolicy implements ApprovalPolicyService {

    /** 策略来源标识（配置取值与审计署名共用）。 */
    public static final String SOURCE = "interactive";

    /** 发布服务的插件 Context（惰性寻址交互服务）。 */
    private final Context context;

    public InteractivePolicy(Context context) {
        this.context = context;
    }

    @Override
    public ApprovalDecision decide(String toolName, JsonNode args) {
        InteractionService answers;
        try {
            answers = context.as(AnswersView.class).answers();
        } catch (Exception e) {
            // 服务缺位 = 无人在场 = fail-closed（交互缺失永不等于默许）
            return ApprovalDecision.deny("交互服务不可用，无人应答（fail-closed）", SOURCE);
        }
        InteractionAnswer answer = answers.ask(InteractionRequest.approval(toolName, argsSummary(args)));
        if (answer.approved()) {
            return ApprovalDecision.allow(SOURCE);
        }
        return ApprovalDecision.deny(
                InteractionAnswer.SOURCE_FAIL_CLOSED.equals(answer.source())
                        ? "无人应答（fail-closed）"
                        : "被人拒绝（回答者: " + answer.source() + "）",
                SOURCE);
    }

    /** 参数摘要（回答者呈现用；截断防长参数刷屏）。 */
    private static String argsSummary(JsonNode args) {
        String text = String.valueOf(args);
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
