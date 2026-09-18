package dev.duo.harness.hooks;

import java.util.List;

/**
 * matcher 匹配（ADR-0019 决策 3）。工单 03 档：absent/空白/`*` = 全匹配，其余整串
 * 精确匹配；`|`/`,` 多选与正则两档随工单 04 补全（Claude Code matcher 三档语义）。
 */
final class HookMatcher {

    private HookMatcher() {
    }

    /** 本调用是否命中该 matcher 表达式。 */
    static boolean matches(String matcher, String toolName) {
        if (matcher == null || matcher.isBlank() || "*".equals(matcher)) {
            return true;
        }
        return matcher.equals(toolName);
    }

    /** 命中本次工具名的全部处理器（按配置声明序）。 */
    static List<HookHandler> matched(List<HookRule> rules, String toolName) {
        return rules.stream()
                .filter(rule -> matches(rule.matcher(), toolName))
                .flatMap(rule -> rule.handlers().stream())
                .toList();
    }
}
