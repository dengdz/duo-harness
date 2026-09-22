package dev.duo.harness.tools.fs;

import dev.duo.harness.tools.Answerer;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 「总是允许」规则生成包装层（M24 工单 02，ADR-0026 决策一）：拦截委托答案返回的
 * {@code allowAlways} 语义，生成 allow 规则（项目级写 settings.json、会话级更新
 * 运行时态并经 {@code sessionSink} 落 {@code permission/rules} 事件），随后把答案
 * 归一为普通 allow 上抛——审计桥与审批策略只见到常规放行，生成是包装层的可组合
 * 关注点（不想要生成就不包这层，a/s 键随规则服务缺席自然不出现）。
 *
 * <p>生成动作只认 approval 类请求；{@link PermissionRules#allowRuleFor} 返回 null
 * （高危根命令、无法提取首词）时答案归一为<b>拒绝</b>——与 CLI「非候选按 a/s
 * 一律拒绝」fail-closed 对齐（Web 卡片无法预知候选态，服务端权威裁决）。</p>
 *
 * <p>线程约定：委托答案器串行作答；规则写入由 {@link PermissionRules} 内部同步。</p>
 */
public final class RuleGeneratingAnswerer implements Answerer {

    private final Answerer delegate;
    private final PermissionRules rules;
    /** 会话级规则事件落盘口（入参 = 更新后全量快照 JSON；null = 只更新运行时态不落事件）。 */
    private final Consumer<String> sessionSink;

    public RuleGeneratingAnswerer(Answerer delegate, PermissionRules rules, Consumer<String> sessionSink) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.sessionSink = sessionSink;
    }

    /** 亲和路由透传（M19，ADR-0020 决策 7）：装饰不改回答者的呈现位归属。 */
    @Override
    public String presenterId() {
        return delegate.presenterId();
    }

    @Override
    public InteractionAnswer answer(InteractionRequest request) {
        InteractionAnswer answer = delegate.answer(request);
        if (answer.alwaysScope() == null
                || !InteractionRequest.KIND_APPROVAL.equals(request.kind())) {
            return answer;
        }
        PermissionRules.Rule rule = PermissionRules.allowRuleFor(
                request.subject(), request.args(),
                InteractionAnswer.SCOPE_SESSION.equals(answer.alwaysScope())
                        ? PermissionRules.Scope.SESSION : PermissionRules.Scope.PROJECT);
        if (rule == null) {
            // 高危/无法提取首词：与非候选 CLI 语义对齐——按拒绝处理（fail-closed）
            return InteractionAnswer.deny(answer.source());
        }
        if (rule.scope() == PermissionRules.Scope.PROJECT) {
            rules.addProjectRule(rule);
        } else {
            String snapshot = rules.addSessionRule(rule);
            if (sessionSink != null) {
                sessionSink.accept(snapshot);
            }
        }
        return InteractionAnswer.allow(answer.source());
    }
}
