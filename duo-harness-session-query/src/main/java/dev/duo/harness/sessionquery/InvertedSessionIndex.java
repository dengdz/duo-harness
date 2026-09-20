package dev.duo.harness.sessionquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.session.SessionEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内存倒排索引实现（一期，ADR-0022 决策 8）：懒构建——构造零 I/O，首次搜索
 * 才扫会话目录；此后每次搜索按文件 mtime+size 戳增量刷新（新文件建索引、
 * 变化文件重建、消失文件摘除）。只扫目录顶层 {@code *.jsonl}——子代理会话
 * （{@code subagents/} 子目录）天然不索引；坏行跳过不炸穿（对齐 titleOf 的
 * 容错纪律）；本进程持独占锁的活跃会话跳过（POSIX 释放陷阱规避——索引开读
 * 该文件再关闭 fd 会释放属主锁，代价是当前会话不参与检索）。
 * 所有方法在内部锁内执行（工具侧并发安全声明依赖）。
 *
 * <p>量级边界：事件全文与词表常驻内存，个人会话规模（千级会话 × 百级事件）
 * 够用；大会话库转 SQLite FTS5——{@link SessionQueryService} 接口语义已
 * 对齐 FTS5 形态，切换不动上层（limitations 记档）。</p>
 */
public final class InvertedSessionIndex implements SessionQueryService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** snippet 窗口：首个命中前 60 字符、窗口总量 160 字符（截断以 … 标注）。 */
    static final int SNIPPET_BEFORE = 60;
    static final int SNIPPET_TOTAL = 160;

    private final Path sessionsDir;
    private final Object lock = new Object();
    /** 会话 id → 文件索引（refreshLocked 维护与目录的一致性）。 */
    private final Map<String, FileEntry> files = new HashMap<>();

    /** 单事件索引条目（仅可检索事件入列）。 */
    private record EventDoc(int eventIndex, String type, long at, String text) { }

    /** 单文件索引：戳（mtime+size）+ 标题（呈现用，不入检索）+ 倒排表。 */
    private record FileEntry(long mtime, long size, String title,
                             List<EventDoc> docs, Map<String, List<Integer>> postings) { }

    public InvertedSessionIndex(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
    }

    @Override
    public List<SessionHit> search(String query, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正: " + limit);
        }
        List<String> tokens = QueryTokenizer.tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<String> phrases = cjkRuns(query);
        List<SessionHit> hits = new ArrayList<>();
        synchronized (lock) {
            refreshLocked();
            for (Map.Entry<String, FileEntry> file : files.entrySet()) {
                SessionHit hit = bestMatch(file.getKey(), file.getValue(), tokens, phrases);
                if (hit != null) {
                    hits.add(hit);
                }
            }
        }
        hits.sort((a, b) -> {
            if (a.score() != b.score()) {
                return Integer.compare(b.score(), a.score());
            }
            return Long.compare(b.lastModifiedMs(), a.lastModifiedMs());
        });
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    /** 短语主键权重：短语次数 × 本权重 + 词频——真说过原词的事件压过单字散落的长文。 */
    private static final int PHRASE_WEIGHT = 1_000;

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

    /**
     * 单会话最强匹配事件：倒排表取查询词并集候选（AND——任一词缺席即无命中），
     * 逐候选按词频总分定强，最强者为该会话命中（并列取较新事件）。
     */
    private SessionHit bestMatch(String sessionId, FileEntry entry, List<String> tokens,
                                 List<String> phrases) {
        List<Integer> candidates = null;
        for (String token : tokens) {
            List<Integer> posting = entry.postings().get(token);
            if (posting == null) {
                return null; // AND：任一词全文件无命中即出局
            }
            if (candidates == null) {
                candidates = posting;
                continue;
            }
            candidates = intersect(candidates, posting);
            if (candidates.isEmpty()) {
                return null;
            }
        }
        EventDoc best = null;
        int bestScore = 0;
        for (int docIndex : candidates) {
            EventDoc doc = entry.docs().get(docIndex);
            int tokenScore = 0;
            for (String token : tokens) {
                tokenScore += countOccurrences(doc.text(), token);
            }
            int phraseScore = 0;
            for (String phrase : phrases) {
                phraseScore += countOccurrences(doc.text(), phrase);
            }
            // 短语次数为主键（× 权重压过词频噪音——长文件单字散落再多也不及真说过原词）、词频为次键
            int score = phraseScore * PHRASE_WEIGHT + tokenScore;
            if (best == null || score > bestScore
                    || (score == bestScore && doc.at() > best.at())) {
                best = doc;
                bestScore = score;
            }
        }
        return new SessionHit(sessionId, entry.title(), entry.mtime(),
                best.eventIndex(), best.type(), best.at(),
                snippet(best.text(), tokens, phrases), bestScore);
    }

    private static List<Integer> intersect(List<Integer> a, List<Integer> b) {
        List<Integer> out = new ArrayList<>(Math.min(a.size(), b.size()));
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            int compare = Integer.compare(a.get(i), b.get(j));
            if (compare == 0) {
                out.add(a.get(i));
                i++;
                j++;
            } else if (compare < 0) {
                i++;
            } else {
                j++;
            }
        }
        return out;
    }

    /** 增量刷新（锁内）：目录顶层 *.jsonl 对账——新/变文件重建索引、消失文件摘除。 */
    private void refreshLocked() {
        if (!Files.isDirectory(sessionsDir)) {
            files.clear(); // 目录缺席（首次启动未建）：空索引；presenters 建目录后下次搜索自然收录
            return;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        try (var list = Files.list(sessionsDir)) {
            for (Path path : list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                String id = path.getFileName().toString().replace(".jsonl", "");
                seen.add(id);
                if (dev.duo.harness.session.Session.heldByThisProcess(path)) {
                    // 活跃会话（本进程持独占锁）：跳过并摘除陈旧条目——索引若开读该文件，
                    // 关闭读取 fd 会按 POSIX 语义释放本进程属主锁（review-log M19 模式①，
                    // 收口审查 P1）。活跃会话内容在内存里是活的，不入检索无碍
                    files.remove(id);
                    continue;
                }
                try {
                    long mtime = Files.getLastModifiedTime(path).toMillis();
                    long size = Files.size(path);
                    FileEntry existing = files.get(id);
                    if (existing != null && existing.mtime() == mtime && existing.size() == size) {
                        continue; // 戳未变：沿用既有索引
                    }
                    files.put(id, indexFile(id, path, mtime, size));
                } catch (IOException e) {
                    // 单文件读取失败跳过：检索是尽力而为的读放大路径，不炸穿搜索
                }
            }
        } catch (IOException e) {
            // 目录遍历失败：保留既有索引（陈旧数据好过空结果），下次搜索重试
        }
        files.keySet().removeIf(id -> !seen.contains(id));
    }

    /** 全量扫一个会话文件建索引：坏行跳过；title latest-wins 仅作呈现元数据。 */
    private FileEntry indexFile(String id, Path path, long mtime, long size) throws IOException {
        List<EventDoc> docs = new ArrayList<>();
        Map<String, List<Integer>> postings = new HashMap<>();
        String title = null;
        int lineNo = -1; // 事件日志下标（与 Session.append 的序号同义：非空行序）
        try (var reader = Files.newBufferedReader(path, java.nio.charset.StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                lineNo++;
                IndexedEvent event;
                try {
                    event = parseLine(line);
                } catch (IOException badLine) {
                    continue; // 坏行跳过（脏文件不因检索放大为故障）
                }
                if (SessionEvent.TITLE.equals(event.type())) {
                    title = event.text();
                    continue;
                }
                String searchable = EventTextExtractor.searchableText(event);
                if (searchable == null || searchable.isBlank()) {
                    continue;
                }
                docs.add(new EventDoc(lineNo, event.type(), event.at(), searchable));
                for (String token : QueryTokenizer.tokenize(searchable)) {
                    postings.computeIfAbsent(token, k -> new ArrayList<>()).add(docs.size() - 1);
                }
            }
        }
        return new FileEntry(mtime, size, title, List.copyOf(docs), Map.copyOf(postings));
    }

    private IndexedEvent parseLine(String line) throws IOException {
        try {
            JsonNode node = JSON.readTree(line);
            JsonNode nameNode = node.get("toolName");
            return new IndexedEvent(node.path("type").asText(),
                    node.path("at").asLong(),
                    node.path("text").asText(""),
                    nameNode == null || nameNode.isNull() ? null : nameNode.asText());
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("事件行解析失败", e);
        }
    }

    // ---- 词频与 snippet ----

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
