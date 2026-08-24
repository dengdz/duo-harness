package dev.duo.harness.tools;

import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.Context;

/**
 * 工具域挂载插件：apply 时发布 "tools" 服务；无配置。
 * 配置树中一行即可启用整个工具域，依赖它的工具插件随后激活。
 */
public final class ToolsPlugin implements Plugin<Void> {

    @Override
    public Class<Void> configType() {
        return null;
    }

    @Override
    public Disposable apply(Context ctx, Void config) {
        dev.duo.harness.tools.internal.ToolsServiceImpl impl =
                new dev.duo.harness.tools.internal.ToolsServiceImpl(ctx);
        return ctx.provide(ToolsService.SERVICE_NAME, impl);
    }
}
