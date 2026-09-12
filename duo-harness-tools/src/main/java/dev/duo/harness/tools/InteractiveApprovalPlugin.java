package dev.duo.harness.tools;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.tools.internal.ApprovalGate;
import dev.duo.harness.tools.internal.InteractivePolicy;

import java.util.Set;

/**
 * 交互审批插件：发布 {@code interactive} 审批策略为 "approval" 服务——
 * ask 委托交互 seam（"answers"）由在场回答者作答（ADR-0008）。
 *
 * <p>与 {@link ApprovalPlugin}（always-deny / auto-approve）二选一挂载：同一
 * 服务名的占坑规则天然互斥。inject 声明 answers——交互审批是回答者的**消费者**，
 * 走标准服务注入；answers 缺位时本插件 PENDING（点名可见），工具域退回
 * "未配置即拒"，安全语义不破。</p>
 *
 * <p>无配置项（yml 一行挂载）。回答者不在场或人未作答一律 fail-closed，
 * 不做"永久放行"。</p>
 */
public final class InteractiveApprovalPlugin implements Plugin<Void> {

    @Override
    public Set<String> inject() {
        return Set.of(InteractionService.SERVICE_NAME);
    }

    @Override
    public Class<Void> configType() {
        return null;
    }

    @Override
    public Disposable apply(Context ctx, Void config) {
        ApprovalPolicyService policy = new InteractivePolicy(ctx);
        Disposable published = ctx.provide(ApprovalPolicyService.SERVICE_NAME, policy);
        Disposable gate = ctx.on(ToolsService.PRE_EXECUTE, ApprovalGate.gateFor(policy));
        return () -> {
            gate.dispose();
            published.dispose();
        };
    }
}
