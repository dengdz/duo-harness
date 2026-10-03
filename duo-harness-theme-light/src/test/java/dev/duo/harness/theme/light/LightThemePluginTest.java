package dev.duo.harness.theme.light;

import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.web.PresentationRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 亮色主题样板用例（M36 工单 04）：optionalInject 声明闸门、注册声明内容
 * （id/展示名/值集覆盖面）、移除后可再注册（卸了再装替换语义）。
 */
class LightThemePluginTest {

    /** 录制型假件：同 id 冲突点名拒绝（对齐真实现口径），供 claimInto 直测。 */
    private static final class RecordingRegistry implements PresentationRegistry {
        ThemeDeclaration claimed;
        int claimCount;

        @Override
        public Disposable claimTheme(ThemeDeclaration theme) {
            if (claimed != null && claimed.themeId().equals(theme.themeId())) {
                throw new PluginException("主题 \"" + theme.themeId() + "\" 已被注册");
            }
            claimed = theme;
            claimCount++;
            return () -> claimed = null;
        }

        @Override
        public Disposable registerCard(CardDeclaration card) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ThemeDeclaration> themes() {
            return claimed == null ? List.of() : List.of(claimed);
        }

        @Override
        public List<CardDeclaration> cards() {
            return List.of();
        }
    }

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：LightThemePluginTest —— 亮色样板：声明闸门/注册内容/移除再装（3 用例） ===");
    }

    @Test
    void optionalInjectDeclaresPresentationRegistry() {
        assertTrue(new LightThemePlugin().optionalInject()
                        .contains(PresentationRegistry.SERVICE_NAME),
                "可选依赖声明与服务名逐字一致（M34 声明闸门教训）");
        assertEquals(Void.class, new LightThemePlugin().configType());
    }

    @Test
    void claimIntoRegistersFullLightPalette() {
        RecordingRegistry registry = new RecordingRegistry();

        Disposable unclaimer = LightThemePlugin.claimInto(registry);

        assertNotNull(unclaimer);
        assertEquals(1, registry.claimCount);
        assertEquals(LightThemePlugin.THEME_ID, registry.claimed.themeId());
        assertEquals("亮色（内置样板）", registry.claimed.displayName());
        Map<String, String> tokens = new LinkedHashMap<>(registry.claimed.tokens());
        for (String must : new String[]{"--bg-page", "--surface", "--surface-2", "--border",
                "--text", "--brand", "--danger", "--ok", "--warn", "--on-brand", "--on-danger",
                "--diff-del-bg", "--shadow-drawer", "--agent-color-7"}) {
            assertTrue(tokens.containsKey(must), "亮色值集须覆盖 " + must);
        }
        assertTrue(tokens.size() >= 30, "值集覆盖面（颜色/投影全量），实际 " + tokens.size());
    }

    @Test
    void disposeThenReclaimReplacesTheme() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();

        LightThemePlugin.claimInto(registry).dispose();
        Disposable second = LightThemePlugin.claimInto(registry);

        assertNotNull(second, "移除后同 id 可重新申请（卸了再装 = 替换语义）");
        assertEquals(2, registry.claimCount);
        assertEquals(LightThemePlugin.THEME_ID, registry.claimed.themeId());
    }

    @Test
    void duplicateClaimWithoutDisposalIsNamed() {
        RecordingRegistry registry = new RecordingRegistry();
        LightThemePlugin.claimInto(registry);

        PluginException conflict = assertThrows(PluginException.class,
                () -> LightThemePlugin.claimInto(registry));
        assertTrue(conflict.getMessage().contains(LightThemePlugin.THEME_ID),
                conflict.getMessage());
    }
}
