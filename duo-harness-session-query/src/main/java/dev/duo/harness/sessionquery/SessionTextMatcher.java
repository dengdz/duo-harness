package dev.duo.harness.sessionquery;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索精算与 snippet 工具（M26-02 自内存倒排实现迁出）：词频计分、汉字原词短语
 * 加权、就近窗口摘录——FTS5 候选精算与活跃会话的 live 内存匹配共用同一套
 * 语义（SessionQueryService 契约的排序与 snippet 条款在此单一实现）。
 */
public final class SessionTextMatcher {

    /** snippet 窗口：首个命中前 60 字符、窗口总量 160 字符（截断以 … 标注）。 */
    static final int SNIPPET_BEFORE = 60;
    static final int SNIPPET_TOTAL = 160;

    /** 短语主键权重：短语次数 × 本权重 + 词频——真说过原词的事件压过单字散落的长文。 */
    static final int PHRASE_WEIGHT = 1_000;

    /** 精算候选：FTS 候选行或 live 事件的统一形态。 */
    public record Candidate(int eventIndex, String type, long at, String text) {
    }

    private SessionTextMatcher() {
    }

    /**
     * 候选集中取最强匹配事件（短语次数 × 权重 + 词频总分定强，并列取较新事件）
     * ——「每会话至多一条最强命中」契约的裁决点。候选须已满足 AND（FTS MATCH
     * 或 {@link #containsAllTokens} 保证），本方法只排名次。
     *
     * @return 该会话的命中；候选无一有正分为 null
     */
    public static SessionHit best(String sessionId, String title, long lastModifiedMs,
                                  List<Candidate> candidates, List<String> tokens,
                                  List<String> phrases) {
        Candidate best = null;
        int bestScore = 0;
        for (Candidate candidate : candidates) {
            int tokenScore = 0;
            for (String token : tokens) {
                tokenScore += countOccurrences(candidate.text(), token);
            }
            int phraseScore = 0;
            for (String phrase : phrases) {
                phraseScore += countOccurrences(candidate.text(), phrase);
            }
            // 短语次数为主键（× 权重压过词频噪音——长文件单字散落再多也不及真说过原词）、词频为次键
            int score = phraseScore * PHRASE_WEIGHT + tokenScore;
            if (best == null || score > bestScore
                    || (score == bestScore && candidate.at() > best.at())) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null || bestScore < 1) {
            return null;
        }
        return new SessionHit(sessionId, title, lastModifiedMs,
                best.eventIndex(), best.type(), best.at(),
                snippet(best.text(), tokens, phrases), bestScore);
    }

    /** AND 预检（live 内存路径）：文本是否包含全部查询词（英文整词边界、汉字字面）。 */
    public static boolean containsAllTokens(String text, List<String> tokens) {
        for (String token : tokens) {
            if (countOccurrences(text, token) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 查询里的连续汉字串（长度 ≥ 2，去重保序）：单字 AND 之上的原词加分与 snippet
     * 锚定依据——"附件库"应当命中并突出真正说过"附件库"的事件，而不是任何
     * 附/件/库三字散落的长文。
     */
    static List<String> cjkRuns(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> runs = new java.util.LinkedHashSet<>();
        StringBuilder run = new StringBuilder();
        int i = 0;
        while (i < query.length()) {
            int codePoint = query.codePointAt(i);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                run.appendCodePoint(codePoint);
            } else {
                flushRun(run, runs);
            }
            i += Character.charCount(codePoint);
        }
        flushRun(run, runs);
        return List.copyOf(runs);
    }

    private static void flushRun(StringBuilder run, java.util.LinkedHashSet<String> runs) {
        if (run.length() >= 2) {
            runs.add(run.toString());
        }
        run.setLength(0);
    }

    /** 词在文本中的出现次数（英文词按整词边界、大小写不敏感；汉字按字面）。 */
    static int countOccurrences(String text, String token) {
        Matcher matcher = tokenPattern(token).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** token 的匹配器：英文词带边界环视，汉字字面（分词规则两侧同口径）。 */
    private static Pattern tokenPattern(String token) {
        if (token.chars().allMatch(c -> c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')) {
            return Pattern.compile("(?<![a-zA-Z0-9_])" + Pattern.quote(token) + "(?![a-zA-Z0-9_])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
        return Pattern.compile(Pattern.quote(token));
    }

    /**
     * snippet：锚点优先取查询短语原词的首次出现（单字 AND 的命中散落在长文各处时，
     * 只有锚在"附件库"这种原词上窗口才有意义），无短语时取命中最密集的区间；
     * 窗口内命中词以 {@code 【】} 包裹，截断侧以 … 标注。命中区间先合并（相邻/重叠
     * ——汉字单字分词使一个词的各字符是相邻命中，不合并不成【苹果】只成【苹】【果】）
     * 再标记。
     */
    static String snippet(String text, List<String> tokens, List<String> phrases) {
        List<int[]> ranges = new ArrayList<>();
        for (String token : tokens) {
            Matcher matcher = tokenPattern(token).matcher(text);
            while (matcher.find()) {
                ranges.add(new int[]{matcher.start(), matcher.end()});
            }
        }
        if (ranges.isEmpty()) {
            return head(text);
        }
        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : ranges) {
            if (!merged.isEmpty() && range[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], range[1]); // 相邻/重叠并入前区间
            } else {
                merged.add(range);
            }
        }
        int anchor = merged.get(0)[0];
        if (!phrases.isEmpty()) {
            int phrasePos = -1;
            for (String phrase : phrases) {
                int at = text.indexOf(phrase);
                if (at >= 0 && (phrasePos < 0 || at < phrasePos)) {
                    phrasePos = at;
                }
            }
            if (phrasePos >= 0) {
                for (int[] range : merged) {
                    if (range[1] > phrasePos) {
                        anchor = range[0]; // 覆盖短语首字的区间（短语的每字都是词元，必被覆盖）
                        break;
                    }
                }
            }
        } else {
            anchor = densestAnchor(merged);
        }
        int start = Math.max(0, anchor - SNIPPET_BEFORE);
        int end = Math.min(text.length(), start + SNIPPET_TOTAL);
        StringBuilder out = new StringBuilder();
        if (start > 0) {
            out.append('…');
        }
        int cursor = start;
        for (int[] range : merged) {
            if (range[1] <= cursor || range[0] >= end) {
                continue; // 窗口外或已覆盖
            }
            int from = Math.max(range[0], cursor);
            out.append(text, cursor, from).append('【')
                    .append(text, from, Math.min(range[1], end)).append('】');
            cursor = Math.min(range[1], end);
        }
        out.append(text, cursor, end);
        if (end < text.length()) {
            out.append('…');
        }
        return out.toString();
    }

    /** 命中最密集区间的锚点（多词查询窗口尽量罩住最多的命中；并列取最先）。 */
    private static int densestAnchor(List<int[]> merged) {
        int bestStart = merged.get(0)[0];
        int bestCount = -1;
        for (int[] range : merged) {
            int limit = range[0] + SNIPPET_TOTAL;
            int count = 0;
            for (int[] other : merged) {
                if (other[0] >= range[0] && other[0] < limit) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                bestStart = range[0];
            }
        }
        return bestStart;
    }

    private static String head(String text) {
        return text.length() <= SNIPPET_TOTAL ? text
                : text.substring(0, SNIPPET_TOTAL) + '…';
    }
}
