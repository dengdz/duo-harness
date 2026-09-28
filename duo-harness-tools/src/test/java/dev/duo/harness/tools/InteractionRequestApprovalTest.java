package dev.duo.harness.tools;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 套件：InteractionRequestApprovalTest —— 批准判定单点（C2 工单 05）：计划复核按
 * options[0] 位置命中（文案不敏感——此前审批工具按文案、审计桥按位置两套判据，
 * 改文案/调顺序即审计错记）、审批类按 approved 布尔（4 用例）。
 */
class InteractionRequestApprovalTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：InteractionRequestApprovalTest —— 批准判定单点：计划复核位置约定"
                + "（文案不敏感）、审批布尔语义、边界（4 用例） ===");
    }

    @Test
    void planApprovalHitsFirstOptionRegardlessOfWording() {
        // 位置约定是判据：即使批准文案换词（或第三方 plan 请求用别的措辞），命中
        // 首选项即批准——文案敏感的旧工具判据正是分叉根源
        InteractionRequest request = InteractionRequest.plan("exit_plan_mode", "计划",
                List.of("批准，开始执行", "重新拟定"));
        assertTrue(InteractionRequest.isApproved(request,
                InteractionAnswer.answered(List.of("批准，开始执行"), "web")),
                "命中首选项即批准");

        InteractionRequest reworded = InteractionRequest.plan("exit_plan_mode", "计划",
                List.of("同意按此执行", "再改改"));
        assertTrue(InteractionRequest.isApproved(reworded,
                InteractionAnswer.answered(List.of("同意按此执行"), "web")),
                "文案换词不影响位置判据");
    }

    @Test
    void planSecondOptionOrEmptyAnswerIsNotApproved() {
        InteractionRequest request = InteractionRequest.plan("exit_plan_mode", "计划",
                List.of("批准，开始执行", "重新拟定"));
        assertFalse(InteractionRequest.isApproved(request,
                InteractionAnswer.answered(List.of("重新拟定"), "web")),
                "命中第二项（要求重拟）不构成批准");
        // 空 values 场景由 answered 工厂校验挡在构造点（至少一项），单点判定的
        // 空值检查是防御层——不重复构造非法对象验证
        assertFalse(InteractionRequest.isApproved(
                InteractionRequest.plan("t", "p", List.of()),
                InteractionAnswer.answered(List.of("随便"), "web")),
                "无选项的 plan 请求不构成批准");
    }

    @Test
    void approvalKindFollowsApprovedFlag() {
        InteractionRequest request = InteractionRequest.approval("bash", "{}");
        assertTrue(InteractionRequest.isApproved(request, InteractionAnswer.allow("cli")),
                "审批放行");
        assertFalse(InteractionRequest.isApproved(request, InteractionAnswer.deny("cli")),
                "审批拒绝");
    }

    @Test
    void questionKindFollowsApprovedFlagByContract() {
        // 非计划、非审批类（提问）按 approved 布尔——提问回答恒 true（answered 语义）
        InteractionRequest request = InteractionRequest.question("选哪个",
                List.of("A", "B"), false, null);
        assertTrue(InteractionRequest.isApproved(request,
                InteractionAnswer.answered(List.of("A"), "cli")));
    }
}
