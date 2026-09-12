package dev.duo.harness.example.agentrepl;

import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.Answerer;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;

/**
 * 审计回答者（装饰器，ADR-0008 决策 5）：审批交互的会话留痕——委托真实回答者
 * 作答，前后写 `approval/requested` / `approval/decided` 事件。机制核不依赖会话；
 * 留痕是装配层可组合的关注点（不想要留痕就不包这层）。
 *
 * <p>提问类请求直接透传（问与答已由 tool/call、tool/result 事件覆盖）。</p>
 */
public final class AuditingAnswerer implements Answerer {

    private final Session session;
    private final Answerer delegate;

    public AuditingAnswerer(Session session, Answerer delegate) {
        this.session = session;
        this.delegate = delegate;
    }

    @Override
    public InteractionAnswer answer(InteractionRequest request) {
        if (!InteractionRequest.KIND_APPROVAL.equals(request.kind())) {
            return delegate.answer(request);
        }
        session.append(SessionEvent.approvalRequested(request.subject(), request.detail()));
        InteractionAnswer answer = delegate.answer(request);
        if (answer != null) {
            String decision = (answer.approved() ? "allow" : "deny")
                    + "（回答者: " + answer.source() + "）";
            session.append(SessionEvent.approvalDecided(request.subject(), decision));
        }
        return answer;
    }
}
