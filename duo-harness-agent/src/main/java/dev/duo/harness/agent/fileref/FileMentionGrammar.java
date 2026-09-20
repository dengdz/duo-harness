package dev.duo.harness.agent.fileref;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @file 路径提及的 grammar（M21 工单 07，ADR-0022）：@token 识别 + mention 格式化。
 * 纯函数、零 I/O——输入/输出全部字符串。
 *
 * <p>规则对齐 DSH grammar.ts：@ 前须行首/空白（邮箱不触发）；引号路径 {@code @"..."}
 * 可含空格；控制字符路径拒绝生成。</p>
 */
final class FileMentionGrammar {

    private FileMentionGrammar() { }

    /**
     * 提取光标前的活跃 @token（补全触发用）。
     *
     * @return 活跃 token（不含 @ 前缀），无活跃 token 返回 null
     */
    static String activeToken(String line) {
        if (line == null) return null;
        int at = lastTriggerAt(line);
        return at >= 0 ? line.substring(at + 1) : null;
    }

    /** 最后一个 @ 触发位置（-1 = 无触发）。 */
    private static int lastTriggerAt(String line) {
        for (int i = line.length() - 1; i >= 0; i--) {
            if (line.charAt(i) != '@') continue;
            if (i == 0 || Character.isWhitespace(line.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    /** 格式化 mention：目录补尾 `/`、含空格才加引号、控制字符拒绝（返回 null）。 */
    static String formatMention(String path, boolean isDirectory) {
        if (path == null || path.isBlank()) return null;
        for (char c : path.toCharArray()) {
            if (Character.isISOControl(c)) return null;
        }
        String suffix = isDirectory && !path.endsWith("/") ? "/" : "";
        String p = path + suffix;
        boolean hasSpace = p.chars().anyMatch(Character::isWhitespace);
        return hasSpace ? "@\"" + p + "\"" : "@" + p;
    }

    /** 是否 @ 开头的提及行（模型指南判断用）。 */
    static boolean isMentionLine(String line) {
        return line != null && line.strip().startsWith("@");
    }
}
