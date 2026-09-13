package dev.duo.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 计划呈交工具（M7 计划模式的批准闭环）：模型完成设计后调用，把计划全文经
 * 交互 seam 呈交用户复核——批准 = 写 plan/mode exited 事件并开始执行；
 * 打回 = 结果携带用户反馈继续改计划；无回答者 / 未作答 fail-closed
 * （计划不批准，模型可见原因）。
 *
 * <p>复核选项固定两个：批准（精确匹配"批准，开始执行"）或打回（选第二项 /
 * 自由文本反馈）。打回不是错误——结果为正常形态，模型据此继续修改计划。</p>
 *
 * @param approveCallback 批准后的装配侧回调（摘除计划指导片段等；由装配层提供）
 */
public final class ExitPlanModeTool implements ToolDefinition {

    /** 工具名（模型侧调用名）。 */
    public static final String NAME = "exit_plan_mode";

    /** 批准选项的精确文本。 */
    public static final String APPROVE_OPTION = "批准，开始执行";

    private static final String RETYPE_OPTION = "继续计划（可直接输入你的修改意见）";

    private final InteractionService answers;
    private final java.util.function.Supplier<Session> session;
    private final Runnable approvedCallback;

    public ExitPlanModeTool(InteractionService answers, java.util.function.Supplier<Session> session,
                            Runnable approvedCallback) {
        this.answers = Objects.requireNonNull(answers, "answers");
        this.session = Objects.requireNonNull(session, "session");
        this.approvedCallback = Objects.requireNonNull(approvedCallback, "approvedCallback");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "呈交计划等待用户批准。仅在计划模式下、完成完整设计后调用；"
                + "参数 plan 为完整计划（markdown）。用户批准后立即开始执行；"
                + "被要求继续时按用户反馈修改计划后再次呈交。";
    }

    @Override
    public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {"type":"object","properties":{
                      "plan":{"type":"string","description":"完整计划（markdown）：目标、步骤、涉及文件"}},
                     "required":["plan"]}""");
        } catch (Exception e) {
            throw new IllegalStateException("exit_plan_mode 参数 schema 内置错误", e);
        }
    }

    @Override
    public String execute(ToolExecution execution) {
        JsonNode planNode = execution.args().get("plan");
        if (planNode == null || planNode.isNull() || planNode.asText().isBlank()) {
            throw new PluginException(NAME + " 缺少必填参数 plan（完整计划内容）");
        }
        String plan = planNode.asText();
        InteractionAnswer answer = answers.ask(InteractionRequest.question(
                "请审阅以下计划：\n" + plan,
                List.of(APPROVE_OPTION, RETYPE_OPTION), false));
        if (answer == null || !answer.approved() || answer.values().isEmpty()) {
            // fail-closed：无回答者 / 未作答 → 计划不批准（对齐 DSH：保持计划模式，
            // 不写 exited——模型可继续修改计划或由用户 /plan off 手动退出）
            return "计划复核无人应答（fail-closed），计划未获批准。请继续完善计划，"
                    + "或等待用户回来后再次呈交（用户也可用 /plan off 手动退出计划模式）。";
        }
        String verdict = answer.values().get(0);
        if (APPROVE_OPTION.equals(verdict)) {
            session.get().append(PlanMode.exitedEvent());
            approvedCallback.run();
            return "计划已获批准（回答者: " + answer.source() + "）。请立即开始执行计划。";
        }
        return "计划未获批准，用户要求继续修改计划。用户反馈：" + verdict
                + "（回答者: " + answer.source() + "）。请按反馈修改后再次呈交。";
    }
}
