package dev.duo.harness.web;

import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 呈现贡献口单元用例（M36 工单 03）：主题/展示卡申请与快照、同名冲突点名拒绝、
 * token 值白名单（url 函数与字符集外字符拒收）、移除器幂等与再申请。
 */
class PresentationContributionsTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：PresentationContributionsTest —— 呈现贡献口：申请/快照/冲突点名/"
                + "白名单/移除幂等（6 用例） ===");
    }

    private final PresentationContributions registry = new PresentationContributions();

    private static PresentationRegistry.ThemeDeclaration theme(String id, Map<String, String> tokens) {
        return new PresentationRegistry.ThemeDeclaration(id, "主题 " + id, tokens);
    }

    @Test
    void claimThemeRegistersSnapshotAndRemovalIsIdempotent() throws Exception {
        Disposable unclaimer = registry.claimTheme(theme("test-dark",
                Map.of("--bg", "#0b0f14", "--accent", "rgba(86,134,254,1)")));

        assertEquals(1, registry.themes().size());
        assertEquals("test-dark", registry.themes().get(0).themeId());
        assertEquals("#0b0f14", registry.themes().get(0).tokens().get("--bg"));

        unclaimer.dispose();
        assertEquals(0, registry.themes().size(), "移除器注销主题");
        unclaimer.dispose();
        assertEquals(0, registry.themes().size(), "移除器幂等（重复执行安全）");

        registry.claimTheme(theme("test-dark", Map.of("--bg", "#000")));
        assertEquals(1, registry.themes().size(), "注销后同 id 可重新申请（卸了再装 = 替换语义）");
    }

    @Test
    void duplicateThemeIdAndToolNameAreNamed() {
        registry.claimTheme(theme("test-dark", Map.of("--bg", "#0b0f14")));
        PluginException themeConflict = assertThrows(PluginException.class,
                () -> registry.claimTheme(theme("test-dark", Map.of("--bg", "#000"))));
        assertTrue(themeConflict.getMessage().contains("test-dark"), themeConflict.getMessage());

        registry.registerCard(new PresentationRegistry.CardDeclaration(
                "web_search", "globe", "网页搜索", List.of("query")));
        PluginException cardConflict = assertThrows(PluginException.class,
                () -> registry.registerCard(new PresentationRegistry.CardDeclaration(
                        "web_search", "globe", "另一张卡", List.of())));
        assertTrue(cardConflict.getMessage().contains("web_search"), cardConflict.getMessage());
    }

    @Test
    void urlFunctionAndForeignCharsetAreRejected() {
        PluginException urlValue = assertThrows(PluginException.class, () -> registry.claimTheme(
                theme("evil", Map.of("--bg", "url(https://evil.example/x.png)"))));
        assertTrue(urlValue.getMessage().contains("url"), urlValue.getMessage());

        PluginException foreignChar = assertThrows(PluginException.class, () -> registry.claimTheme(
                theme("evil2", Map.of("--bg", "red;color:javascript:alert(1)"))));
        assertTrue(foreignChar.getMessage().contains("白名单外字符"), foreignChar.getMessage());

        PluginException blankValue = assertThrows(PluginException.class, () -> registry.claimTheme(
                theme("evil3", Map.of("--bg", "  "))));
        assertTrue(blankValue.getMessage().contains("不得为空"), blankValue.getMessage());
    }

    @Test
    void malformedThemeIdAndTokenNameAreNamed() {
        assertThrows(PluginException.class, () -> registry.claimTheme(
                theme("Bad_Id", Map.of("--bg", "#000"))), "大写/下划线 id 拒收");
        assertThrows(PluginException.class, () -> registry.claimTheme(
                theme("ok-id", Map.of("bg", "#000"))), "缺 -- 前缀的 token 名拒收");
    }

    @Test
    void cardRegistrationSnapshotAndRemoval() {
        registry.registerCard(new PresentationRegistry.CardDeclaration(
                "web_search", "globe", "网页搜索", List.of("query", "limit")));

        assertEquals(1, registry.cards().size());
        assertEquals("web_search", registry.cards().get(0).toolName());
        assertEquals(List.of("query", "limit"), registry.cards().get(0).summaryFields());

        assertThrows(PluginException.class, () -> registry.registerCard(
                new PresentationRegistry.CardDeclaration("Bad-Name", "x", "x", List.of())),
                "非法工具名拒收");
    }

    @Test
    void snapshotsAreCopiesNotLiveViews() {
        Map<String, String> tokens = new HashMap<>();
        tokens.put("--bg", "#0b0f14");
        registry.claimTheme(theme("test-dark", tokens));

        List<PresentationRegistry.ThemeDeclaration> snapshot = registry.themes();
        tokens.put("--accent", "#f00");
        assertEquals(1, snapshot.get(0).tokens().size(), "快照不受原 Map 后续变更影响");
    }
}
