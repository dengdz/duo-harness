package dev.duo.harness.sessionquery;

import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite FTS5 检索引擎（M26-02，ADR-0028 决策一）：{@link SessionQueryService} 的
 * 生产实现，替代内存倒排索引（M21 一期形态）。
 *
 * <p><b>派生只读层</b>：索引库 {@code <sessionsDir>/index.db} 是可丢弃的加速层——
 * 会话 JSONL 仍是唯一权威数据；schema 版本不符就地整库重建、库文件删除后自愈重建。
 * 懒构建语义与一期一致：构造零 I/O，首次搜索才开库与扫目录，此后按文件 mtime+size
 * 戳增量对账。</p>
 *
 * <p><b>中文分词</b>（unicode61 + 外部分词）：入库侧把汉字串预分词为逐字空格分隔
 * （unicode61 视连续汉字为一个 token，与「汉字逐字 AND」的查询语义不符），查询侧
 * 用同一 {@link QueryTokenizer} 切词后逐词加引号包裹成字面短语——FTS5 查询语法
 * 永不执行、SQL 全参数化绑定（模型 query 含任意特殊字符不引发注入或报错）。</p>
 *
 * <p><b>live 供数</b>（M21#1 消除）：本进程持锁的活跃会话不入库也不碰其文件
 * （POSIX 锁释放陷阱——任何 fd 的开关都会放掉属主锁），内容经
 * {@link Session#heldSession} 注册表从内存直取，与库内命中合并参与同一排序。</p>
 *
 * <p><b>排序契约</b>：FTS5 MATCH 只做候选筛选（AND 语义与倒排交集同构，按 bm25
 * 取前 {@link #CANDIDATE_CAP} 行），「每会话一条最强命中」（汉字原词短语加权 +
 * 词频）与 snippet 由 {@link SessionTextMatcher} 单一实现精算——两路径同语义。</p>
 *
 * <p>所有方法在内部锁内串行（单连接 + 工具侧并发安全声明依赖）；{@link #close()}
 * 关库连接，由装配插件（SessionQueryPlugin）在拆树时调用。</p>
 */
public final class FtsSessionIndex implements SessionQueryService, AutoCloseable {

    /** 库结构版本：不符即就地整库重建（派生层自愈，不迁移）。 */
    static final int SCHEMA_VERSION = 1;

    /** FTS5 候选上限：MATCH 命中按 bm25 取前 N 行进精算（个人会话规模全命中也在此内）。 */
    static final int CANDIDATE_CAP = 500;

    /** 库文件名（置于会话目录内——目录扫描只认 *.jsonl，互不干扰；删库即重建）。 */
    private static final String DB_FILE = "index.db";

    /** 会话文件后缀（目录扫描与 live 路径共用，不做字面量重复）。 */
    private static final String JSONL_SUFFIX = ".jsonl";

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(FtsSessionIndex.class);

    private final Path sessionsDir;
    private final Path dbFile;
    private final Object lock = new Object();
    private Connection conn;

    public FtsSessionIndex(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
        this.dbFile = sessionsDir.resolve(DB_FILE);
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
        List<String> phrases = SessionTextMatcher.cjkRuns(query);
        List<SessionHit> hits = new ArrayList<>();
        synchronized (lock) {
            ensureOpenLocked();
            List<String> liveIds = refreshLocked();
            hits.addAll(searchPersisted(matchExpression(tokens), tokens, phrases));
            hits.addAll(searchLive(liveIds, tokens, phrases));
        }
        hits.sort((a, b) -> {
            if (a.score() != b.score()) {
                return Integer.compare(b.score(), a.score());
            }
            return Long.compare(b.lastModifiedMs(), a.lastModifiedMs());
        });
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // 关库失败无可补救：连接随进程/GC 释放
                }
                conn = null;
            }
        }
    }

    // ---- 库生命周期 ----

    /** 开库（懒，锁内）：WAL + schema 版本校验——不符（空库/旧库）就地整库重建；
     * 库文件损坏（打开/执行失败）删文件重建一次。 */
    private void ensureOpenLocked() {
        if (conn != null) {
            return;
        }
        try {
            conn = openConnection();
            if (querySchemaVersion(conn) == SCHEMA_VERSION) {
                return; // 版本相符 = 表结构即当前形态
            }
            // 版本不符（0 = 全新空库 / 其他 = 旧 schema）：派生层不迁移，就地整库重建
            exec(conn, "DROP TABLE IF EXISTS doc_meta",
                    "DROP TABLE IF EXISTS docs",
                    "DROP TABLE IF EXISTS sessions");
            createSchema(conn);
        } catch (SQLException | RuntimeException e) {
            conn = rebuildBrokenDb(e);
        }
    }

    private Connection openConnection() throws SQLException {
        // 绕开 DriverManager：其驱动自动发现按系统 classloader 扫描（且 JVM 内只跑一次），
        // 在 exec:java / 插件 realm 等 classloader 形态下找不到 org.sqlite.JDBC——
        // SQLiteDataSource 直连不经过 DriverManager，任何装载形态一致
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile);
        Connection opened = ds.getConnection();
        exec(opened, "PRAGMA journal_mode=WAL");
        return opened;
    }

    /** 损坏自愈：删库文件（含 WAL/SHM 边车——孤儿 WAL 会在重建库上重放致损坏，
     * SQLite 官方要求三者同删）后从零重建；再失败才 fail-loud。 */
    private Connection rebuildFromScratch(Throwable cause) {
        log.warn("索引库不可用，就地重建: {}", dbFile, cause);
        closeQuietly(conn);
        conn = null;
        try {
            Files.deleteIfExists(dbFile);
            Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-wal"));
            Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-shm"));
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            throw new PluginException("索引库损坏且删除失败: " + dbFile, e);
        }
        try {
            Connection opened = openConnection();
            createSchema(opened);
            return opened;
        } catch (SQLException e) {
            throw new PluginException("索引库重建失败: " + dbFile, e);
        }
    }

    private Connection rebuildBrokenDb(Throwable cause) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // 已损坏的连接，关闭失败无碍重建
            }
        }
        return rebuildFromScratch(cause);
    }

    private static void createSchema(Connection c) throws SQLException {
        exec(c, "CREATE TABLE IF NOT EXISTS sessions("
                        + "session_id TEXT PRIMARY KEY, title TEXT, "
                        + "mtime INTEGER NOT NULL, size INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS doc_meta("
                        + "doc_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "session_id TEXT NOT NULL, event_index INTEGER NOT NULL, "
                        + "type TEXT NOT NULL, at INTEGER NOT NULL, text TEXT NOT NULL)",
                "CREATE VIRTUAL TABLE IF NOT EXISTS docs USING fts5(text)",
                "CREATE INDEX IF NOT EXISTS idx_doc_meta_session ON doc_meta(session_id)");
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA user_version=" + SCHEMA_VERSION);
        }
    }

    private static int querySchemaVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static void exec(Connection c, String... sqls) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String sql : sqls) {
                st.execute(sql);
            }
        }
    }

    private static void closeQuietly(Connection c) {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // 清理路径
            }
        }
    }

    // ---- 增量对账 ----

    /**
     * 目录对账（锁内）：非活跃文件按 mtime+size 戳增量重建；消失文件摘除；
     * 活跃会话（本进程持锁）从库摘除并返回其文件路径列表（live 匹配的供数范围）。
     */
    private List<String> refreshLocked() {
        List<String> liveIds = new ArrayList<>();
        if (!Files.isDirectory(sessionsDir)) {
            execQuiet("DELETE FROM doc_meta", "DELETE FROM sessions"); // 独立语句——不依赖多语句执行语义
            return liveIds; // 目录缺席（首次启动未建）：空索引
        }
        Map<String, long[]> indexed = indexedStamps();
        java.util.Set<String> seen = new java.util.HashSet<>();
        try (var list = Files.list(sessionsDir)) {
            for (Path path : list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(JSONL_SUFFIX)).toList()) {
                String id = path.getFileName().toString().replace(JSONL_SUFFIX, "");
                seen.add(id);
                if (Session.heldByThisProcess(path)) {
                    // 活跃会话：不入库（文件不可触碰，POSIX 锁释放陷阱），摘除陈旧库条目；
                    // 内容经 heldSession 注册表走 live 内存匹配。仅在既有索引在场时才删——
                    // 每次搜索对活跃会话空转 DELETE 是无谓的 WAL 写放大
                    if (indexed.containsKey(id)) {
                        removeSessionQuiet(id);
                    }
                    liveIds.add(id);
                    continue;
                }
                try {
                    long mtime = Files.getLastModifiedTime(path).toMillis();
                    long size = Files.size(path);
                    long[] stamp = indexed.get(id);
                    if (stamp != null && stamp[0] == mtime && stamp[1] == size) {
                        continue; // 戳未变：沿用既有索引
                    }
                    reindex(id, path, mtime, size);
                } catch (IOException | SQLException e) {
                    // 单文件失败跳过：检索是尽力而为的读放大路径，不炸穿搜索
                    // （陈旧/缺席索引随下次对账重试；栈进日志可观测）
                    log.warn("会话索引重建跳过: {}", id, e);
                }
            }
        } catch (IOException e) {
            // 目录遍历失败：保留既有索引（陈旧数据好过空结果），下次搜索重试
            return liveIds;
        }
        for (String id : indexed.keySet()) {
            if (!seen.contains(id)) {
                removeSessionQuiet(id); // 文件消失（删除/归档）：摘除
            }
        }
        return liveIds;
    }

    private Map<String, long[]> indexedStamps() {
        Map<String, long[]> out = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT session_id, mtime, size FROM sessions")) {
            while (rs.next()) {
                out.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)});
            }
        } catch (SQLException e) {
            throw new PluginException("索引库读取失败: " + dbFile, e);
        }
        return out;
    }

    private void removeSessionQuiet(String id) {
        try (PreparedStatement delDocs = conn.prepareStatement(
                "DELETE FROM docs WHERE rowid IN (SELECT doc_id FROM doc_meta WHERE session_id=?)");
             PreparedStatement delMeta = conn.prepareStatement(
                     "DELETE FROM doc_meta WHERE session_id=?");
             PreparedStatement delSession = conn.prepareStatement(
                     "DELETE FROM sessions WHERE session_id=?")) {
            conn.setAutoCommit(false);
            for (PreparedStatement ps : List.of(delDocs, delMeta, delSession)) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuiet();
            // 摘除失败保留陈旧条目（下次对账重试），不炸穿搜索；栈进日志可观测
            log.warn("会话索引摘除失败: {}", id, e);
        } finally {
            // 置位/复位对称：失败路径不得滞留手动提交态（否则后续写落入永不提交的事务）
            restoreAutoCommitQuiet();
        }
    }

    /** 全量重建单会话索引（单事务）：坏行跳过；版本头跳过且不计事件序号（M26-01）；title latest-wins。 */
    private void reindex(String id, Path path, long mtime, long size) throws IOException, SQLException {
        List<Object[]> rows = new ArrayList<>();
        String title = null;
        int lineNo = -1; // 事件日志下标（与 Session.append 序号同义）
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (dev.duo.harness.session.SessionFormat.isHeaderLine(line)) {
                    continue; // 版本头不是事件（M26-01）：跳过且不计序号
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
                // eventIndex 用文件事件序号（lineNo，与 Session.append 序号同义）——
                // 不入索引的事件也占号，与内存实现/复合游标口径一致
                rows.add(new Object[]{lineNo, event.type(), event.at(), searchable});
            }
        }
        conn.setAutoCommit(false);
        try (PreparedStatement delDocs = conn.prepareStatement(
                "DELETE FROM docs WHERE rowid IN (SELECT doc_id FROM doc_meta WHERE session_id=?)");
             PreparedStatement delMeta = conn.prepareStatement(
                     "DELETE FROM doc_meta WHERE session_id=?");
             PreparedStatement delSession = conn.prepareStatement(
                     "DELETE FROM sessions WHERE session_id=?");
             PreparedStatement insSession = conn.prepareStatement(
                     "INSERT INTO sessions(session_id, title, mtime, size) VALUES(?,?,?,?)");
             PreparedStatement insMeta = conn.prepareStatement(
                     "INSERT INTO doc_meta(session_id, event_index, type, at, text) VALUES(?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS);
             PreparedStatement insDoc = conn.prepareStatement(
                     "INSERT INTO docs(rowid, text) VALUES(?,?)")) {
            for (PreparedStatement ps : List.of(delDocs, delMeta, delSession)) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            insSession.setString(1, id);
            insSession.setString(2, title);
            insSession.setLong(3, mtime);
            insSession.setLong(4, size);
            insSession.executeUpdate();
            for (Object[] row : rows) {
                insMeta.setString(1, id);
                insMeta.setInt(2, (Integer) row[0]);
                insMeta.setString(3, (String) row[1]);
                insMeta.setLong(4, (Long) row[2]);
                insMeta.setString(5, (String) row[3]);
                insMeta.executeUpdate();
                try (ResultSet keys = insMeta.getGeneratedKeys()) {
                    if (keys.next()) {
                        insDoc.setLong(1, keys.getLong(1));
                        insDoc.setString(2, tokenizeForIndex((String) row[3]));
                        insDoc.executeUpdate();
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuiet();
            throw e;
        } finally {
            restoreAutoCommitQuiet();
        }
    }

    private IndexedEvent parseLine(String line) throws IOException {
        try {
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(line);
            com.fasterxml.jackson.databind.JsonNode nameNode = node.get("toolName");
            return new IndexedEvent(node.path("type").asText(),
                    node.path("at").asLong(),
                    node.path("text").asText(""),
                    nameNode == null || nameNode.isNull() ? null : nameNode.asText());
        } catch (RuntimeException e) {
            throw new IOException("事件行解析失败", e);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ---- 查询 ----

    /**
     * MATCH 表达式：查询词逐个加引号包裹为字面短语、空格连接（FTS5 空格 = AND，
     * 与倒排交集语义同构）。词元由 {@link QueryTokenizer} 自产（小写 ASCII 词与
     * 单汉字），天然不含 FTS5 语法字符；引号包裹再堵一层——查询语法永不执行。
     */
    static String matchExpression(List<String> tokens) {
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append('"').append(token).append('"');
        }
        return out.toString();
    }

    /**
     * 入库预分词：汉字逐字空格分隔（unicode61 视连续汉字为一个 token，须手工拆
     * 到字级才与查询侧「汉字逐字」同口径），其余原样（ASCII 词由 unicode61 切，
     * 大小写折叠两端一致）。
     */
    static String tokenizeForIndex(String raw) {
        StringBuilder out = new StringBuilder(raw.length() * 2);
        int i = 0;
        while (i < raw.length()) {
            int codePoint = raw.codePointAt(i);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
                out.appendCodePoint(codePoint).append(' ');
            } else {
                out.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return out.toString().strip();
    }

    private List<SessionHit> searchPersisted(String matchExpr, List<String> tokens,
                                             List<String> phrases) {
        Map<String, List<SessionTextMatcher.Candidate>> bySession = new LinkedHashMap<>();
        Map<String, String> titles = new HashMap<>();
        Map<String, Long> mtimes = new HashMap<>();
        String sql = "SELECT m.session_id, m.event_index, m.type, m.at, m.text, s.title, s.mtime"
                + " FROM doc_meta m JOIN docs ON docs.rowid = m.doc_id"
                + " JOIN sessions s ON s.session_id = m.session_id"
                + " WHERE docs MATCH ? ORDER BY rank LIMIT " + CANDIDATE_CAP;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, matchExpr);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sessionId = rs.getString(1);
                    bySession.computeIfAbsent(sessionId, k -> new ArrayList<>())
                            .add(new SessionTextMatcher.Candidate(rs.getInt(2), rs.getString(3),
                                    rs.getLong(4), rs.getString(5)));
                    titles.put(sessionId, rs.getString(6));
                    mtimes.put(sessionId, rs.getLong(7));
                }
            }
        } catch (SQLException e) {
            throw new PluginException("索引库查询失败: " + dbFile, e);
        }
        List<SessionHit> hits = new ArrayList<>();
        for (Map.Entry<String, List<SessionTextMatcher.Candidate>> entry : bySession.entrySet()) {
            SessionHit hit = SessionTextMatcher.best(entry.getKey(),
                    titles.get(entry.getKey()), mtimes.getOrDefault(entry.getKey(), 0L),
                    entry.getValue(), tokens, phrases);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    /** live 匹配：活跃会话事件从内存直取（白名单同口径），与库内命中同语义精算。 */
    private List<SessionHit> searchLive(List<String> liveIds, List<String> tokens,
                                        List<String> phrases) {
        List<SessionHit> hits = new ArrayList<>();
        for (String id : liveIds) {
            Session live = Session.heldSession(sessionsDir.resolve(id + JSONL_SUFFIX));
            if (live == null) {
                continue; // 对账与匹配之间被关闭：本次跳过，下次对账自然回库
            }
            List<SessionTextMatcher.Candidate> candidates = new ArrayList<>();
            List<SessionEvent> events = live.events();
            for (int i = 0; i < events.size(); i++) {
                SessionEvent event = events.get(i);
                String searchable = EventTextExtractor.searchableText(new IndexedEvent(
                        event.type(), event.at(), event.text(), event.toolName()));
                if (searchable == null || searchable.isBlank()) {
                    continue;
                }
                if (SessionTextMatcher.containsAllTokens(searchable, tokens)) {
                    candidates.add(new SessionTextMatcher.Candidate(i, event.type(),
                            event.at(), searchable));
                }
            }
            // 活跃即最新：mtime 取当前时刻（文件不可触碰——stat 也要开 fd，POSIX 陷阱）
            SessionHit hit = SessionTextMatcher.best(id, live.title(),
                    System.currentTimeMillis(), candidates, tokens, phrases);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    private void execQuiet(String... sqls) {
        try (Statement st = conn.createStatement()) {
            for (String sql : sqls) {
                st.execute(sql);
            }
        } catch (SQLException ignored) {
            // 尽力而为路径
        }
    }

    private void rollbackQuiet() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // 回滚失败随连接状态终结
        }
    }

    private void restoreAutoCommitQuiet() {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 连接已异常，后续 ensureOpen 重建
        }
    }
}
