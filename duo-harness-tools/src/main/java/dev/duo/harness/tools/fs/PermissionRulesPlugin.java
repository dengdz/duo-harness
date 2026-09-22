package dev.duo.harness.tools.fs;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;

import java.util.Set;

/**
 * 权限规则插件（M24，ADR-0026 决策一）：发布 {@code permission-rules} 服务——
 * 项目根（.git 标定，缺省 cwd）加载 {@code .duo/settings.json} 的 permissions 段，
 * 供审批链规则前置裁决（{@link WorkspaceApprovalPlugin} 可选注入）与
 * {@code /permission rules} 命令面共用。不挂本插件即无规则能力，装配零感回退
 * （审批链视同无规则、命令面降级提示）。
 *
 * <p>无配置项（yml 一行挂载）；与 fs/approval 无硬依赖——纯对话装配挂载亦合法
 * （规则裁决面无人消费，等待 bash/审批插件到位）。</p>
 */
public final class PermissionRulesPlugin implements Plugin<Void> {

    @Override
    public Set<String> inject() {
        return Set.of();
    }

    @Override
    public Class<Void> configType() {
        return null;
    }

    @Override
    public Disposable apply(Context ctx, Void config) {
        PermissionRules rules = PermissionRules.load(
                PermissionRules.findProjectRoot(java.nio.file.Path.of(System.getProperty("user.dir"))));
        return ctx.provide(PermissionRules.SERVICE_NAME, rules);
    }
}
