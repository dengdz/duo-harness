package dev.duo.harness.sessionquery;

import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FTS5 引擎实现测试（M26-02 接缝 1）：契约用例集全量继承 + 引擎特有面——
 * 活跃会话 live 供数（M21#1 消除）、schema 版本不符就地重建、库文件损坏自愈、
 * 模型 query 字面化（FTS5 语法字符不炸）、入库预分词纯函数。
 */
class FtsSessionIndexTest extends SessionQueryServiceContractTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：FtsSessionIndexTest —— FTS5 引擎：契约 17 用例全量继承"
                + "（AND/逐字/每会话一条/短语加权/排序含 mtime 并列/snippet/坏行/白名单/整词大小写/limit/目录缺席/并发安全）"
                + "+ 特有 9 面：live 供数（M21#1 消除）、schema 不符重建、库损坏自愈、字面化防注入、"
                + "增量拾取与摘除、subagents 目录不索引、表达式与预分词纯函数、版本头对齐（26 用例） ===");
    }

    @Override
    SessionQueryService createIndex(Path sessionsDir, Path cwd) {
        return new FtsSessionIndex(sessionsDir, cwd);
    }

    @TempDir
    Path liveDir;

    /** 手造会话头行（cwd=liveDir——M26-03 授权边界内的可检索形态；SessionFormat 同源序列化）。 */
    private void writeHeader(Path jsonl) throws Exception {
        Files.writeString(jsonl, dev.duo.harness.session.SessionFormat.headerLine(
                        dev.duo.harness.session.SessionFormat.CURRENT_VERSION, liveDir) + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private static void writeEvent(Path jsonl, String type, long at, String text)
            throws Exception {
        // ObjectMapper 正规序列化——text 含引号（如交付声明的路径数组 JSON）自动转义
        var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("type", type).put("at", at).put("text", text);
        Files.writeString(jsonl, node.toString() + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    @Test
    void legacyOrForeignCwdSessionsExcluded() throws Exception {
        // M26-03 授权边界（ADR-0028 决策三）：仅同目录会话可搜——无头旧会话与
        // 异目录会话（防跨项目上下文污染）不进结果；live 同边界
        Path sessions = liveDir.resolve("sessions");
        Files.createDirectories(sessions);
        // 无头旧会话（M26 前）与异 cwd 会话与同 cwd 会话各一
        Files.writeString(sessions.resolve("20260926-190000-0001.jsonl"),
                "{\"type\":\"user/message\",\"at\":1,\"text\":\"无头旧会话关键词石斛\"}\n");
        Files.writeString(sessions.resolve("20260926-190000-0002.jsonl"),
                "{\"type\":\"session\",\"version\":1,\"cwd\":\"/other/project\"}\n"
                        + "{\"type\":\"user/message\",\"at\":2,\"text\":\"异目录会话关键词石斛\"}\n");
        Path own = sessions.resolve("20260926-190000-0003.jsonl");
        writeHeader(own);
        writeEvent(own, "user/message", 3, "本目录会话关键词石斛");
        // live 异目录：持锁会话 cwd 与查询侧不同
        Session foreign = Session.create(sessions, java.nio.file.Path.of("/other/project"));
        foreign.append(SessionEvent.userMessage("异目录活跃会话关键词石斛"));

        try (FtsSessionIndex index = new FtsSessionIndex(sessions, liveDir)) {
            List<SessionHit> hits = index.search("石斛", 8);
            assertEquals(1, hits.size(), "仅同 cwd 会话命中（persisted 三选一 + live 异目录排除）");
            assertEquals("20260926-190000-0003", hits.get(0).sessionId());
        } finally {
            foreign.close();
        }
    }

    @Test
    void liveSessionSearchableWhileHeld() throws Exception {
        // M21#1 消除（M26-02 核心）：本进程持锁的活跃会话可搜——内容经注册表从内存直取
        Path sessions = liveDir.resolve("sessions");
        Session live = Session.create(sessions, liveDir);
        live.append(SessionEvent.userMessage("活跃会话里的独有关键词昙花"));
        Session closed = Session.create(sessions, liveDir);
        closed.append(SessionEvent.userMessage("已关闭会话的关键词月季"));
        closed.close();

        try (FtsSessionIndex index = new FtsSessionIndex(sessions, liveDir)) {
            List<SessionHit> liveHits = index.search("昙花", 8);
            assertEquals(1, liveHits.size(), "活跃会话可搜（live 供数）");
            assertEquals(live.id(), liveHits.get(0).sessionId());
            assertTrue(liveHits.get(0).snippet().contains("【昙花】"));

            List<SessionHit> closedHits = index.search("月季", 8);
            assertEquals(1, closedHits.size(), "已关闭会话经库检索");
            assertEquals(closed.id(), closedHits.get(0).sessionId());

            assertEquals(2, index.search("关键词", 8).size(), "live 与库内命中合并同排序");
        } finally {
            live.close();
        }
    }

    @Test
    void schemaVersionMismatchRebuildsInPlace() throws Exception {
        // 预置一个 user_version 不符的库（模拟旧版 schema）：搜索触发就地整库重建
        Path sessions = liveDir.resolve("sessions");
        Files.createDirectories(sessions);
        writeHeader(sessions.resolve("20260926-170000-0001.jsonl"));
        writeEvent(sessions.resolve("20260926-170000-0001.jsonl"),
                "user/message", 1, "重建后的关键词蒲公英");
        Path db = sessions.resolve("index.db");
        try (java.sql.Connection raw = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             var st = raw.createStatement()) {
            st.execute("PRAGMA user_version=999");
            st.execute("CREATE TABLE sessions(x)"); // 旧表结构（缺列）——版本不符即弃
        }

        try (FtsSessionIndex index = new FtsSessionIndex(sessions, liveDir)) {
            List<SessionHit> hits = index.search("蒲公英", 8);
            assertEquals(1, hits.size(), "schema 不符就地重建后正常检索");
        }
    }

    @Test
    void corruptDbFileSelfHeals() throws Exception {
        // 库文件写垃圾字节（非 SQLite 格式）：派生层自愈——删库重建
        Path sessions = liveDir.resolve("sessions");
        Files.createDirectories(sessions);
        writeHeader(sessions.resolve("20260926-170000-0002.jsonl"));
        writeEvent(sessions.resolve("20260926-170000-0002.jsonl"),
                "user/message", 1, "自愈后的关键词油菜花");
        Files.writeString(sessions.resolve("index.db"), "这不是一个合法的 SQLite 库文件".repeat(10));

        try (FtsSessionIndex index = new FtsSessionIndex(sessions, liveDir)) {
            List<SessionHit> hits = index.search("油菜花", 8);
            assertEquals(1, hits.size(), "库损坏删文件重建，搜索恢复正常");
        }
    }

    @Test
    void ftsSyntaxInQueryIsLiteralNotExecuted() throws Exception {
        // 模型 query 字面化：FTS5 语法字符（引号/OR/括号/星号）不执行语法、不报错；
        // OR/NEAR 等语法词按普通词元参与 AND——本例词元无全命中，空结果
        Path sessions = liveDir.resolve("sessions");
        Files.createDirectories(sessions);
        writeHeader(sessions.resolve("20260926-170000-0003.jsonl"));
        writeEvent(sessions.resolve("20260926-170000-0003.jsonl"),
                "user/message", 1, "普通内容梅花");

        try (FtsSessionIndex index = new FtsSessionIndex(sessions, liveDir)) {
            List<SessionHit> hits = index.search("梅\"花 OR (梅*) NEAR", 8);
            assertTrue(hits.isEmpty(),
                    "语法字符按字面处理：OR/NEAR 成普通词元参与 AND，本例无完整命中: " + hits);
            // 纯语法字符查询：无有效词元，空结果
            assertTrue(index.search("\"\" OR AND ( ) *", 8).isEmpty());
        }
    }

    @Test
    void incrementalRefreshPicksUpAndDropsFiles() throws Exception {
        // 增量对账（审查补覆盖——checklist「增量刷新」的机器验证）：追加事件拾取、删文件摘除
        Path sessions = liveDir.resolve("sessions");
        Files.createDirectories(sessions);
        Path jsonl = sessions.resolve("20260926-170000-0010.jsonl");
        writeHeader(jsonl);
        writeEvent(jsonl, "user/message", 1, "首轮内容马蹄莲");

        try (FtsSessionIndex index = new FtsSessionIndex(sessions, liveDir)) {
            assertEquals(1, index.search("马蹄莲", 8).size(), "首轮收录");
            writeEvent(jsonl, "user/message", 2, "次轮内容鹤望兰");
            assertEquals(1, index.search("鹤望兰", 8).size(), "mtime/size 戳变化 → 增量拾取新事件");
            Files.delete(jsonl);
            assertTrue(index.search("马蹄莲", 8).isEmpty(), "文件消失 → 索引摘除");
        }
    }

    @Test
    void subagentDirectoryNotIndexed() throws Exception {
        // 子代理会话不入检索（sessions/subagents/ 子目录，与一期口径一致）
        Path sessions = liveDir.resolve("sessions");
        Path sub = sessions.resolve("subagents");
        Files.createDirectories(sub);
        writeEvent(sub.resolve("20260926-170000-0011.jsonl"),
                "user/message", 1, "子代理目录里的独有词虞美人");

        try (FtsSessionIndex index = new FtsSessionIndex(sessions, liveDir)) {
            assertTrue(index.search("虞美人", 8).isEmpty(), "subagents/ 子目录不索引");
        }
    }

    @Test
    void deliverablePresentedSearchableByFileName() throws Exception {
        // M26-04：交付声明入检索——按成果文件名反查会话可命中
        Path sessions = liveDir.resolve("sessions");
        Files.createDirectories(sessions);
        Path jsonl = sessions.resolve("20260926-200000-0012.jsonl");
        writeHeader(jsonl);
        writeEvent(jsonl, "user/message", 1, "帮我出一份验收报告");
        writeEvent(jsonl, "deliverable/presented", 2,
                "[\"/tmp/proj/out/验收报告-v2.md\"]");

        try (FtsSessionIndex index = new FtsSessionIndex(sessions, liveDir)) {
            List<SessionHit> hits = index.search("验收报告", 8);
            assertEquals(1, hits.size(), "按成果文件名反查会话: " + hits);
            assertEquals("deliverable/presented", hits.get(0).eventType(),
                    "最强匹配事件为交付声明本身: " + hits);
        }
    }

    @Test
    void matchExpressionQuotesEachToken() {
        assertEquals("\"苹\" \"果\" \"hard\"", FtsSessionIndex.matchExpression(List.of("苹", "果", "hard")),
                "词元逐个引号包裹、空格连接（AND + 字面化）");
    }

    @Test
    void tokenizeForIndexSplitsHanPerChar() {
        assertEquals("帮 我 看 hard 链 接", FtsSessionIndex.tokenizeForIndex("帮我看hard链接"),
                "汉字逐字空格分隔，ASCII 段原样（unicode61 自切）");
        assertEquals("附 件 库", FtsSessionIndex.tokenizeForIndex("附件库"));
    }

    @Test
    void headerLineSkippedAndEventIndexStaysAligned() throws Exception {
        // M26-01 衔接：带版本头的新会话——头不入索引、eventIndex 与 append 序号同义
        Path sessions = liveDir.resolve("sessions");
        Files.createDirectories(sessions);
        Path jsonl = sessions.resolve("20260926-170000-0004.jsonl");
        writeHeader(jsonl);
        writeEvent(jsonl, "assistant/chunk", 1, "不入索引的流式段");
        writeEvent(jsonl, "user/message", 2, "带版本头会话的关键词山茶");

        try (FtsSessionIndex index = new FtsSessionIndex(sessions, liveDir)) {
            List<SessionHit> hits = index.search("山茶", 8);
            assertEquals(1, hits.size());
            assertEquals(1, hits.get(0).eventIndex(),
                    "头行与 chunk 均占事件序号但不入索引——eventIndex 与 append 序号同义");
        }
    }
}
