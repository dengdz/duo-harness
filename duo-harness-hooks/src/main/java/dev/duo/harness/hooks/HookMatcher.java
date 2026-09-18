package dev.duo.harness.hooks;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * matcher 匹配三档（ADR-0019 决策 3，Claude Code 同款语义）：
 * absent/空白/`*` = 全匹配；纯字母数字与 `_ - 空格 | ,` = 精确名/多选（`|` 或 `,`
 * 分隔）；含其他字符 = 未锚定正则（{@code find} 语义——{@code Edit.*} 亦命中
 * {@code NotebookEdit}，需整串匹配请自行加 {@code ^$}）。
 */
final class HookMatcher {

    /** 精确名/多选形态：字母数字、下划线、连字符、空白、竖线、逗号。 */
    private static final Pattern EXACT_LIST_FORM = Pattern.compile("[\\w\\s|,\\-]+");

    private HookMatcher() {
    }

    /** 本调用是否命中该 matcher 表达式。 */
    static boolean matches(String matcher, String toolName) {
        if (matcher == null || matcher.isBlank() || "*".equals(matcher.trim())) {
            return true;
        }
        String expr = matcher.trim();
        if (isExactListForm(expr)) {
            for (String part : expr.split("[|,]")) {
                if (part.strip().equals(toolName)) {
                    return true;
                }
            }
            return false;
        }
        try {
            // 未锚定正则：Claude Code 的 RegExp.prototype.test 语义
            return Pattern.compile(expr).matcher(toolName).find();
        } catch (PatternSyntaxException e) {
            return false; // 解析期已拦截非法正则，此处防御兜底
        }
    }

    /** 是否精确名/多选形态（解析期据此决定要不要按正则校验）。 */
    static boolean isExactListForm(String matcher) {
        return EXACT_LIST_FORM.matcher(matcher.trim()).matches();
    }

    /**
     * 是否需要按正则编译校验：非全匹配（null/空白/`*`）且非精确名/多选形态。
     * 全匹配不是正则——`*` 单独作正则非法，不能落进编译校验。
     */
    static boolean isRegexForm(String matcher) {
        return matcher != null && !matcher.isBlank() && !"*".equals(matcher.trim())
                && !isExactListForm(matcher);
    }

    /** 命中本次工具名的全部处理器（按配置声明序）。 */
    static List<HookHandler> matched(List<HookRule> rules, String toolName) {
        return rules.stream()
                .filter(rule -> matches(rule.matcher(), toolName))
                .flatMap(rule -> rule.handlers().stream())
                .toList();
    }
}
