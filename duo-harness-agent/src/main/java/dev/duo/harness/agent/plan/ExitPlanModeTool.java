package dev.duo.harness.agent.plan;

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
    /**
     * 发起呈现位 → 装配绑定（M19 亲和路由，ADR-0020 决策 7）：工具实例查重先到先得，
     * 但各呈现位经 {@link #bindSession} 补记自己的会话供给**与批准回调**——plan/mode
     * 事件写进发起方会话、计划清理动作（plan.active 复位、指导片段卸载）也跑发起方的
     * （回调单一绑定会让 Web-first 装配下 CLI 批准的计划指导片段永不卸载）。
     */
    private final java.util.concurrent.ConcurrentMap<String, PresenterBinding>
            bindingsByPresenter = new java.util.concurrent.ConcurrentHashMap<>();
    /** 绑定注册序（确定性回退用——ConcurrentHashMap 无序，"任一"会随机落账）。 */
    private final java.util.List<PresenterBinding> bindingOrder =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 一条呈现位装配绑定：会话供给 + 批准回调。 */
    private record PresenterBinding(java.util.function.Supplier<Session> session,
                                    Runnable approvedCallback) {
    }

    public ExitPlanModeTool(InteractionService answers, String presenterId,
                            java.util.function.Supplier<Session> session,
                            Runnable approvedCallback) {
        this.answers = Objects.requireNonNull(answers, "answers");
        // 供给不即取：惰性供给（holder::current、face::currentSession）在装配期不可用
        bindSession(presenterId, session, approvedCallback);
    }

    /**
     * 补记一个呈现位的装配绑定（后来呈现位装配时调用——工具实例先到先得，各记各账）。
     * 换绑感知由供给器自身保证（呈现位传 holder::current 同款）。
     */
    public void bindSession(String presenterId, java.util.function.Supplier<Session> session,
                            Runnable approvedCallback) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(approvedCallback, "approvedCallback");
        String key = presenterId == null || presenterId.isBlank() ? "" : presenterId;
        PresenterBinding binding = new PresenterBinding(session, approvedCallback);
        if (bindingsByPresenter.putIfAbsent(key, binding) == null) {
            bindingOrder.add(binding);
        }
    }

    /**
     * 发起方的装配绑定：标记缺席（直调等）回退**最早注册**的绑定——确定性落账
     * （ConcurrentHashMap 无序，取"任一"会让 plan/mode 事件随机进某个会话）。
     */
    private PresenterBinding bindingFor(String presenterId) {
        PresenterBinding binding =
                bindingsByPresenter.get(presenterId == null || presenterId.isBlank()
                        ? "" : presenterId);
        if (binding != null) {
            return binding;
        }
        return bindingOrder.isEmpty()
                ? bindingsByPresenter.values().iterator().next() : bindingOrder.get(0);
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
        // 计划复核走 KIND_PLAN（BUG-20260917-04）：subject 携工具名——审计桥据此把
        // 请求/决定写进作答呈现位会话，浏览器计划卡与终端提示都以此身份键渲染；
        // 发起呈现位随请求走（亲和路由 + 会话供给按发起方，M19 ADR-0020 决策 7）
        InteractionAnswer answer = answers.ask(InteractionRequest.plan(
                NAME, plan, List.of(APPROVE_OPTION, RETYPE_OPTION), execution.presenterId()));
        if (answer == null || !answer.approved() || answer.values().isEmpty()) {
            // fail-closed：无回答者 / 未作答 → 计划不批准（对齐 DSH：保持计划模式，
            // 不写 exited——模型可继续修改计划或由用户 /plan off 手动退出）
            return "计划复核无人应答（fail-closed），计划未获批准。请继续完善计划，"
                    + "或等待用户回来后再次呈交（用户也可用 /plan off 手动退出计划模式）。";
        }
        String verdict = answer.values().get(0);
        if (APPROVE_OPTION.equals(verdict)) {
            PresenterBinding binding = bindingFor(execution.presenterId());
            binding.session().get().append(PlanMode.exitedEvent());
            binding.approvedCallback().run();
            return "计划已获批准（回答者: " + answer.source() + "）。请立即开始执行计划。";
        }
        return "计划未获批准，用户要求继续修改计划。用户反馈：" + verdict
                + "（回答者: " + answer.source() + "）。请按反馈修改后再次呈交。";
    }
}
