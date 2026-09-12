package dev.duo.harness.tools;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.tools.internal.InteractionRegistry;

/**
 * 交互插件：发布交互服务（"answers"，ADR-0008 的 seam 机制核）。
 *
 * <p>回答者由各呈现端注册（AgentRepl 的 console answerer、M8 的 web answerer），
 * 本插件只立注册表与询问入口，无配置项。不挂载本插件时交互服务缺位——
 * interactive 审批策略与 ask_user 工具一律 fail-closed。</p>
 */
public final class InteractionPlugin implements Plugin<Void> {

    @Override
    public Class<Void> configType() {
        return null;
    }

    @Override
    public Disposable apply(Context ctx, Void config) {
        return ctx.provide(InteractionService.SERVICE_NAME, new InteractionRegistry());
    }
}
