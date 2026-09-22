package dev.duo.harness.tools.fs;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.tools.AnswersView;
import dev.duo.harness.tools.ApprovalPolicyService;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolsService;
import dev.duo.harness.tools.internal.ApprovalGate;
import dev.duo.harness.tools.internal.InteractivePolicy;

import java.util.Set;

/**
 * 档位审批插件（ADR-0012"权限预设与审批联动"）：发布 {@code approval} 策略为
 * {@link WorkspaceGatePolicy}——档位 ALLOW 短路放行（workspace-write 档区内写、
 * danger 档全量），ASK 委托交互回答者瀑布由人作答。
 *
 * <p>与 {@link dev.duo.harness.tools.InteractiveApprovalPlugin} 互替：挂载了
 * fs 工具族的装配用本插件（写操作免审/审批由档位裁决），纯交互装配用原插件
 * （全部 ask）。同一服务名的占坑规则天然互斥。inject 声明 workspace——无
 * fs 插件提供方时本插件 PENDING（点名可见），此时应改挂 InteractiveApprovalPlugin。</p>
 *
 * <p>无配置项（yml 一行挂载）；ask 落回答者不在场或人未作答一律 fail-closed。</p>
 */
public final class WorkspaceApprovalPlugin implements Plugin<Void> {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WorkspaceApprovalPlugin.class);

    @Override
    public Set<String> inject() {
        return Set.of(InteractionService.SERVICE_NAME, WorkspacePolicy.SERVICE_NAME);
    }

    @Override
    public Class<Void> configType() {
        return null;
    }

    /** workspace 服务的视图接口（方法名即服务名）。 */
    interface WorkspaceView {

        WorkspacePolicy workspace();
    }

    @Override
    public Disposable apply(Context ctx, Void config) {
        PermissionRules rules = resolveRules(ctx);
        ApprovalPolicyService inner = rules == null
                ? new InteractivePolicy(ctx) : new PermissionRulePolicy(rules, new InteractivePolicy(ctx));
        ApprovalPolicyService policy = new WorkspaceGatePolicy(
                inner, ctx.as(WorkspaceView.class).workspace());
        Disposable published = ctx.provide(ApprovalPolicyService.SERVICE_NAME, policy);
        Disposable gate = ctx.on(ToolsService.PRE_EXECUTE, ApprovalGate.gateFor(policy));
        return () -> {
            gate.dispose();
            published.dispose();
        };
    }

    /**
     * 规则服务可选解析：缺席（未挂 permission-rules 插件）或解析失败返回 null——
     * 规则是增益能力（M24，ADR-0026 决策一），审批链零感回退为无规则行为。
     */
    private static PermissionRules resolveRules(Context ctx) {
        try {
            return ctx.hasService(PermissionRules.SERVICE_NAME)
                    ? ctx.as(PermissionRulesView.class).permissionRules() : null;
        } catch (Exception e) {
            log.warn("权限规则服务解析失败，审批链按无规则运行（视为缺席）", e);
            return null;
        }
    }

    /** 权限规则服务视图（方法名即服务名）。 */
    interface PermissionRulesView {

        PermissionRules permissionRules();
    }
}
