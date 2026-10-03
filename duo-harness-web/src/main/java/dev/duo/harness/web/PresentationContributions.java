package dev.duo.harness.web;

import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * {@link PresentationRegistry} 实现：主题表 + 展示卡表，synchronized 串行化
 * 申请/注销（操作者驱动的低频动作，正确性优先）。注册时点名校验（坏声明在
 * 装配期暴露而非运行期半死）；移除器幂等（重复执行/键已易主静默通过）。
 */
final class PresentationContributions implements PresentationRegistry {

    /** token 名形态：{@code --} 前缀 + 小写连字符（主题令牌只增不改名的锚点形态）。 */
    private static final Pattern TOKEN_NAME = Pattern.compile("^--[a-z][a-z0-9-]*$");

    /**
     * token 值白名单：CSS 字面量字符集（正则见串内字符组——字母/数字/空格/井号/百分号/
     * 点/逗/括号/单引号/加/斜杠/连字符/乘号；乘斜连写即块注释雷区，勿入注释——M22 经验档）。
     * 函数调用形态放行 rgba 等常规值，但显式拒收 url 函数（自定义属性值唯一可逃逸成
     * 网络请求的通道）与字符集外字符（冒号/分号/尖括号/反斜杠/双引号/艾特——样式注入面收口）。
     */
    private static final Pattern TOKEN_VALUE = Pattern.compile("^[a-zA-Z0-9 #%.,'()+*/-]+$");
    private static final Pattern URL_FUNCTION = Pattern.compile("url\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** 主题 id → 声明；展示卡工具名 → 声明。 */
    private final Map<String, ThemeDeclaration> themes = new LinkedHashMap<>();
    private final Map<String, CardDeclaration> cards = new LinkedHashMap<>();

    @Override
    public synchronized Disposable claimTheme(ThemeDeclaration theme) {
        Objects.requireNonNull(theme, "theme");
        PresentationRegistry.requireValidThemeId(theme.themeId());
        if (theme.displayName() == null || theme.displayName().isBlank()) {
            throw new PluginException("主题展示名不得为空: " + theme.themeId());
        }
        if (theme.tokens() == null || theme.tokens().isEmpty()) {
            throw new PluginException("主题 token 值集不得为空: " + theme.themeId());
        }
        for (Map.Entry<String, String> token : theme.tokens().entrySet()) {
            if (token.getKey() == null || !TOKEN_NAME.matcher(token.getKey()).matches()) {
                throw new PluginException("token 名须为 -- 前缀小写连字符形态: "
                        + token.getKey() + "（主题 " + theme.themeId() + "）");
            }
            requireLiteralValue(token.getKey(), token.getValue(), theme.themeId());
        }
        if (themes.containsKey(theme.themeId())) {
            throw new PluginException("主题 \"" + theme.themeId() + "\" 已被注册"
                    + "（先注册方在场，重复申请点名拒绝；替换 = 卸了再装）");
        }
        themes.put(theme.themeId(), new PresentationRegistry.ThemeDeclaration(
                theme.themeId(), theme.displayName(), Map.copyOf(theme.tokens())));
        String themeId = theme.themeId();
        return () -> themes.remove(themeId);
    }

    @Override
    public synchronized Disposable registerCard(CardDeclaration card) {
        Objects.requireNonNull(card, "card");
        if (card.toolName() == null || !card.toolName().matches("[a-z][a-z0-9_]*")) {
            throw new PluginException("展示卡工具名须为 [a-z][a-z0-9_]* 形态: " + card.toolName());
        }
        if (card.icon() == null || card.icon().isBlank() || card.label() == null || card.label().isBlank()) {
            throw new PluginException("展示卡图标与名称不得为空: " + card.toolName());
        }
        if (cards.containsKey(card.toolName())) {
            throw new PluginException("工具 \"" + card.toolName() + "\" 的展示卡已被注册"
                    + "（先注册方在场，重复注册点名拒绝；替换 = 卸了再装）");
        }
        cards.put(card.toolName(), new PresentationRegistry.CardDeclaration(
                card.toolName(), card.icon(), card.label(),
                card.summaryFields() == null ? List.of() : List.copyOf(card.summaryFields())));
        String toolName = card.toolName();
        return () -> cards.remove(toolName);
    }

    @Override
    public synchronized List<ThemeDeclaration> themes() {
        return List.copyOf(new ArrayList<>(themes.values()));
    }

    @Override
    public synchronized List<CardDeclaration> cards() {
        return List.copyOf(new ArrayList<>(cards.values()));
    }

    /** token 值字面量白名单（null/空拒收；url() 函数与字符集外字符点名拒绝）。 */
    private static void requireLiteralValue(String name, String value, String themeId) {
        if (value == null || value.isBlank()) {
            throw new PluginException("token 值不得为空: " + name + "（主题 " + themeId + "）");
        }
        if (URL_FUNCTION.matcher(value).find()) {
            throw new PluginException("token 值拒收 url() 函数（改值不改构的安全边界）: "
                    + name + "（主题 " + themeId + "）");
        }
        if (!TOKEN_VALUE.matcher(value).matches()) {
            throw new PluginException("token 值含白名单外字符（仅放行 CSS 字面量）: "
                    + name + " = " + value + "（主题 " + themeId + "）");
        }
    }
}
