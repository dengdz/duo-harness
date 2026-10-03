package dev.duo.harness.theme.light;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.web.PresentationRegistry;

import java.util.Map;
import java.util.Set;

/**
 * 亮色主题样板（M36 工单 04，ADR-0038 决策二）：官方亮色 token 值集——「内置只出
 * 暗色精品、亮色留第三方」裁定的官方示范形态，兼作第三方主题作者的参照实现
 * （与 tool-stats 工具样板同位）。双形态交付：classpath 行直接在场，或
 * {@code mvn package} 产物 jar 置插件目录经插件中心安装（模块 jar 只含自有类，
 * 交货约定同 M35 工单 07）。无 Web 部署下 optionalInject 优雅缺席（哑激活，
 * M35 mountIfWebPresent 先例）。
 */
public final class LightThemePlugin implements Plugin<Void> {

    /** 主题 id（聚合端点与前端选择器键）。 */
    public static final String THEME_ID = "light";

    /**
     * 亮色值集：M36 前的内置亮色（git 历史 theme.css 首版）+ 新增 token 的亮色
     * 对应值。值集只覆盖颜色/投影——几何（radius）与字体（mono）随缺省不重复声明；
     * 值全部过呈现贡献口字面量白名单（无 url 函数、无字符集外字符）。
     */
    public static final Map<String, String> LIGHT_TOKENS = Map.ofEntries(
            Map.entry("--brand", "rgb(86, 134, 254)"),
            Map.entry("--brand-strong", "rgb(65, 118, 230)"),
            Map.entry("--brand-tint", "rgb(237, 243, 254)"),
            Map.entry("--on-brand", "#ffffff"),
            Map.entry("--bg-page", "#eef0f3"),
            Map.entry("--surface", "#ffffff"),
            Map.entry("--surface-2", "#f5f6f7"),
            Map.entry("--border", "rgba(0, 0, 0, 0.1)"),
            Map.entry("--border-strong", "rgba(0, 0, 0, 0.18)"),
            Map.entry("--text", "#1a1f27"),
            Map.entry("--text-dim", "#6b7280"),
            Map.entry("--text-faint", "rgba(26, 31, 39, 0.4)"),
            Map.entry("--danger", "#dc2626"),
            Map.entry("--danger-tint", "#fef2f2"),
            Map.entry("--danger-soft", "rgba(220, 38, 38, 0.75)"),
            Map.entry("--on-danger", "#ffffff"),
            Map.entry("--ok", "#16a34a"),
            Map.entry("--ok-tint", "#dcfce7"),
            Map.entry("--warn", "#d97706"),
            Map.entry("--diff-del-bg", "rgba(220, 38, 38, 0.08)"),
            Map.entry("--diff-add-bg", "rgba(22, 163, 74, 0.08)"),
            Map.entry("--shadow-lv1", "0 2px 4px rgba(0, 0, 0, 0.05)"),
            Map.entry("--shadow-lv2",
                    "0 4px 12px rgba(0, 0, 0, 0.02), 0 2px 8px rgba(0, 0, 0, 0.04)"),
            Map.entry("--shadow-drawer", "-12px 0 34px rgba(15, 23, 42, 0.16)"),
            Map.entry("--agent-color-0", "#b45309"),
            Map.entry("--agent-color-1", "#be123c"),
            Map.entry("--agent-color-2", "#c2410c"),
            Map.entry("--agent-color-3", "#047857"),
            Map.entry("--agent-color-4", "#0e7490"),
            Map.entry("--agent-color-5", "#0369a1"),
            Map.entry("--agent-color-6", "#7c3aed"),
            Map.entry("--agent-color-7", "#be185d"));

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
            return null; // 无 Web 部署：优雅缺席（哑激活；声明闸门内 hasService 守卫）
        }
        return claimInto(ctx.as(PresentationRegistry.PresentationView.class).presentationRegistry());
    }

    /** 注册本体（Context 无关——单测直供 registry 即可验证声明内容与移除语义）。 */
    public static Disposable claimInto(PresentationRegistry registry) {
        return registry.claimTheme(new PresentationRegistry.ThemeDeclaration(
                THEME_ID, "亮色（内置样板）", LIGHT_TOKENS));
    }
}
