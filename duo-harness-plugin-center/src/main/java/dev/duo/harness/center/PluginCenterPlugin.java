package dev.duo.harness.center;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginRows;
import dev.duo.harness.web.WebRouteRegistry;

import java.util.Set;

/**
 * 插件中心（M35，ADR-0037 决策三）：插件生态管理面，独立插件形态——它自己就是
 * "第三方插件形态"的旗舰示范。apply 即发布 {@link PluginCenter} 服务
 * （{@value PluginCenter#SERVICE_NAME}），消费 {@code pluginRows} 行级控制口
 * 驱动容器（inject 声明闸门内读取——S2 装配缝的关键纪律，M34 三盲教训）；
 * Web 部署下经 {@code webRoutes} 贡献口（optionalInject——CLI-only 部署缺席
 * 零感知）挂接 /plugins/center/** 管理 API（工单 06）。
 *
 * <p>opt-in 装载：yml 行 {@code dev.duo.harness.center.PluginCenterPlugin}
 * 在场即启用，不装行零感知。</p>
 */
public final class PluginCenterPlugin implements Plugin<Void> {

    @Override
    public Set<String> inject() {
        return Set.of(PluginRows.SERVICE_NAME);
    }

    @Override
    public Set<String> optionalInject() {
        return Set.of(WebRouteRegistry.SERVICE_NAME);
    }

    @Override
    public Class<Void> configType() {
        return null;
    }

    @Override
    public Disposable apply(Context ctx, Void config) {
        PluginRows rows = ctx.as(PluginCenter.PluginRowsView.class).pluginRows();
        PluginCenter center = new PluginCenter(rows);
        Disposable published = ctx.provide(PluginCenter.SERVICE_NAME, center);
        Disposable bridgeUnmounter = PluginCenterWebBridge.mountIfWebPresent(ctx, center);
        if (bridgeUnmounter != null) {
            ctx.effect(bridgeUnmounter);
        }
        return published;
    }
}
