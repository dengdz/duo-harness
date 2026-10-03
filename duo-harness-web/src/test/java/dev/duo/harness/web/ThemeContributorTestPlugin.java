package dev.duo.harness.web;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;

import java.util.Map;
import java.util.Set;

/**
 * 测试夹具：主题贡献方（S1 装配缝 / Boot 时序头号风险自证，M36 工单 03）——
 * optionalInject 声明 + hasService 守卫：无 Web 部署哑激活优雅缺席；服务在场即
 * 注册主题并把移除器挂本插件作用域（拔除即注销）。主题行先于 web 行装载时，
 * 服务后到经依赖指纹自动重载完成注册（PluginInstance 可选依赖语义在案）。
 */
public final class ThemeContributorTestPlugin implements Plugin<Void> {

    @Override
    public Set<String> optionalInject() {
        return Set.of(PresentationRegistry.SERVICE_NAME);
    }

    @Override
    public Class<Void> configType() {
        return Void.class;
    }

    @Override
    public Disposable apply(Context ctx, Void config) {
        if (!ctx.hasService(PresentationRegistry.SERVICE_NAME)) {
            return null; // 无 Web 部署：优雅缺席（哑激活；声明闸门内 hasService 守卫——M35 先例）
        }
        Disposable unclaimer = ctx.as(PresentationRegistry.PresentationView.class)
                .presentationRegistry()
                .claimTheme(new PresentationRegistry.ThemeDeclaration(
                        "test-dark", "测试暗色", Map.of(
                        "--bg", "#0b0f14",
                        "--accent", "rgba(86,134,254,1)")));
        ctx.effect(unclaimer);
        return null;
    }
}
