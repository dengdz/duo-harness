package dev.duo.harness.agent;

import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.Answerer;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;

import java.util.function.Supplier;

/**
 * 审计回答者（装饰器，ADR-0008 决策 5）：审批与计划复核交互的会话留痕——委托真实
 * 回答者作答，前后写 {@code approval/requested} / {@code approval/decided} 事件。
 * 机制核不依赖会话；留痕是装配层可组合的关注点（不想要留痕就不包这层）。
 *
 * <p>会话经 {@link Supplier} 延迟解析——/new 换会话后留痕落当前会话（CLI 的
 * SessionHolder 与 Web 的 {@code WebFace::currentSession} 同一接法）。提问类
 * 请求直接透传（问与答已由 tool/call、tool/result 事件覆盖）。</p>
 *
 * <p>计划复核（KIND_PLAN）与审批同通道留痕（BUG-20260917-04）：双呈现位部署下
 * CLI 发起的计划请求若不留痕，作答呈现位（行序路由优先 Web）的会话里收不到任何
 * 计划事件——浏览器卡永不渲染、终端回答者轮不到，人机两侧同时静默。事件词汇
 * 复用 approval/*（计划复核即"对计划产物的批准决策"，subject = exit_plan_mode
 * 供前端渲染计划卡形态）。</p>
 */
public final class AuditingAnswerer implements Answerer {

    private final Supplier<Session> session;
    private final Answerer delegate;

    public AuditingAnswerer(Supplier<Session> session, Answerer delegate) {
        this.session = session;
        this.delegate = delegate;
    }

    /** 亲和路由透传（M19，ADR-0020 决策 7）：装饰不改回答者的呈现位归属。 */
    @Override
    public String presenterId() {
        return delegate.presenterId();
    }

    @Override
    public InteractionAnswer answer(InteractionRequest request) {
        if (!InteractionRequest.KIND_APPROVAL.equals(request.kind())
                && !InteractionRequest.KIND_PLAN.equals(request.kind())) {
            return delegate.answer(request);
        }
        // 卡片 id 借 toolCallId 通道（M24 工单 02）：审批卡按 id 精确回填（POST /api/answer）
        session.get().append(SessionEvent.approvalRequested(request.subject(), request.detail(), request.id()));
        InteractionAnswer answer = delegate.answer(request);
        if (answer != null) {
            // 批准判定单点（C2 工单 05）：与审批工具共用同一判据——计划复核按
            // options[0] 命中、审批按 approved 布尔，见 InteractionRequest.isApproved
            String decision = (InteractionRequest.isApproved(request, answer) ? "allow" : "deny")
                    + "（回答者: " + answer.source() + "）";
            session.get().append(SessionEvent.approvalDecided(request.subject(), decision));
        }
        return answer;
    }
}
