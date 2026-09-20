package dev.duo.harness.sessionquery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询分词（一期纯 Java，ADR-0022 决策 8）：小写化后英文/数字按整词切、
 * 汉字逐字成词——无词典分词的量级边界（limitations 记档，SQLite 演进时换
 * FTS5 unicode61 + 外部分词）。切分规则对索引构建与查询两侧同一实现，
 * 保证 AND 语义两端同口径。
 */
public final class QueryTokenizer {

    private QueryTokenizer() { }

    /**
     * 分词（去重保序）：ASCII 字母数字（含下划线）连续段为一个词；汉字
     * （Unicode Script Han）逐字为词；其余字符一律视作分隔符。
     */
    public static List<String> tokenize(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        String lower = input.toLowerCase(java.util.Locale.ROOT);
        StringBuilder word = new StringBuilder();
        int i = 0;
        while (i < lower.length()) {
            int codePoint = lower.codePointAt(i);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                flushWord(word, tokens);
                tokens.add(String.valueOf(Character.toChars(codePoint)));
                i += Character.charCount(codePoint);
                continue;
            }
            char c = lower.charAt(i);
            if (isWordChar(c)) {
                word.append(c);
            } else {
                flushWord(word, tokens);
            }
            i++;
        }
        flushWord(word, tokens);
        return List.copyOf(tokens);
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static void flushWord(StringBuilder word, Set<String> tokens) {
        if (!word.isEmpty()) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }
}
