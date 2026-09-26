package dev.duo.harness.sessionquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionQueryService 契约用例集（M26-02 接缝 1）：把接口 javadoc 的语义契约
 * 钉成可执行用例——多词 AND、每会话至多一条最强命中、排序（分数降序并列
 * mtime 降序）、snippet 形态、坏行跳过、白名单、limit 契约、mtime 并列、整词大小写、并发安全（17 用例）。实现类继承本基类
 * 执行（「转实现上层零改动」承诺的机器验证）；用例不引用任何实现类。
 */
abstract class SessionQueryServiceContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    /** 被测实现工厂：子类提供（每个用例新建实例——懒构建契约从零验证）。 */
    abstract SessionQueryService createIndex(Path sessionsDir);

    private Path sessionsDir() {
        return tempDir.resolve("sessions");
    }

    /** 追加一行事件到会话文件（可选字段非 null 才写——与生产序列化同形）。 */
    private static void appendEvent(Path jsonl, String type, long at, String text,
                                    String toolCallId, String toolName, String reasoning)
            throws IOException {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", type).put("at", at).put("text", text);
        if (toolCallId != null) {
            node.put("toolCallId", toolCallId);
        }
        if (toolName != null) {
            node.put("toolName", toolName);
        }
        if (reasoning != null) {
            node.put("reasoning", reasoning);
        }
        Files.writeString(jsonl, node.toString() + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private Path session(String id) throws IOException {
        Files.createDirectories(sessionsDir());
        return sessionsDir().resolve(id + ".jsonl");
    }

    @Test
    void andSemanticsRequireAllTokens() throws IOException {
        Path a = session("20260926-160000-0001");
        appendEvent(a, "user/message", 1, "苹果与梨的讨论", null, null, null);
        Path b = session("20260926-160000-0002");
        appendEvent(b, "user/message", 2, "只有苹果没有另一种", null, null, null);

        List<SessionHit> hits = createIndex(sessionsDir()).search("苹果 梨", 8);
        assertEquals(1, hits.size(), "两词 AND：只含一词的会话出局");
        assertEquals("20260926-160000-0001", hits.get(0).sessionId());
    }

    @Test
    void chineseMatchesCharByCharAnd() throws IOException {
        Path a = session("20260926-160000-0003");
        appendEvent(a, "user/message", 1, "帮我看看附件库的硬链接去重怎么实现", null, null, null);
        Path b = session("20260926-160000-0004");
        appendEvent(b, "user/message", 2, "无关内容完全不搭", null, null, null);

        List<SessionHit> hits = createIndex(sessionsDir()).search("附件 库 硬链接", 8);
        assertEquals(1, hits.size(), "汉字逐字 AND 命中真说过这些字的会话");
        assertEquals("20260926-160000-0003", hits.get(0).sessionId());
        assertTrue(hits.get(0).snippet().contains("【附件库】"), "相邻命中合并为原词包裹: "
                + hits.get(0).snippet());
    }

    @Test
    void bestMatchPerSessionIsStrongestEvent() throws IOException {
        Path a = session("20260926-160000-0005");
        appendEvent(a, "user/message", 1, "部署部署部署的问题", null, null, null);
        appendEvent(a, "assistant/message", 2, "部署", null, null, null);

        List<SessionHit> hits = createIndex(sessionsDir()).search("部署", 8);
        assertEquals(1, hits.size(), "每会话至多一条命中");
        assertEquals(0, hits.get(0).eventIndex(), "最强匹配 = 词频最高的事件（与 append 序号同义）");
        assertEquals("user/message", hits.get(0).eventType());
    }

    @Test
    void phraseBoostOutranksTokenScatter() throws IOException {
        Path a = session("20260926-160000-0006");
        appendEvent(a, "user/message", 1, "附 件 库 三 字 散 落 的 长 文", null, null, null);
        appendEvent(a, "assistant/message", 2, "附件库", null, null, null);

        List<SessionHit> hits = createIndex(sessionsDir()).search("附件库", 8);
        assertEquals(1, hits.size());
        assertEquals(1, hits.get(0).eventIndex(), "真说过原词的事件压过单字散落的长文（短语加权）");
    }

    @Test
    void scoreDescThenMtimeDescOrdering() throws IOException {
        Path weak = session("20260926-160000-0007");
        appendEvent(weak, "user/message", 1, "苹果", null, null, null);
        try {
            Thread.sleep(5); // 保证 mtime 可区分
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Path strong = session("20260926-160000-0008");
        appendEvent(strong, "user/message", 2, "苹果苹果苹果", null, null, null);

        List<SessionHit> hits = createIndex(sessionsDir()).search("苹果", 8);
        assertEquals(2, hits.size());
        assertEquals("20260926-160000-0008", hits.get(0).sessionId(), "分数降序");
    }

    @Test
    void scoreTieBreaksByNewerMtime() throws IOException {
        // 排序条款后半句：分数并列按会话最近修改时间降序
        Path older = session("20260926-160000-0301");
        appendEvent(older, "user/message", 1, "苹果", null, null, null);
        try {
            Thread.sleep(20); // mtime 可区分
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Path newer = session("20260926-160000-0302");
        appendEvent(newer, "user/message", 2, "苹果", null, null, null);

        List<SessionHit> hits = createIndex(sessionsDir()).search("苹果", 8);
        assertEquals(2, hits.size());
        assertEquals("20260926-160000-0302", hits.get(0).sessionId(),
                "分数并列时新会话排前（mtime 降序）");
    }

    @Test
    void englishWordMatchingIsWholeWordCaseInsensitive() throws IOException {
        Path a = session("20260926-160000-0105");
        appendEvent(a, "user/message", 1, "The MainClass uses PostgreSQL", null, null, null);

        SessionQueryService index = createIndex(sessionsDir());
        assertEquals(1, index.search("mainclass", 8).size(), "大小写不敏感（小写查询命中文内驼峰）");
        assertEquals(1, index.search("PostgreSQL", 8).size(), "整词命中（查询原大小写亦可）");
        assertTrue(index.search("ostgres", 8).isEmpty(), "词中片段不命中（整词语义）");
    }

    @Test
    void snippetWrapsHitsAndMarksTruncation() throws IOException {
        Path a = session("20260926-160000-0009");
        StringBuilder text = new StringBuilder("前".repeat(200));
        text.append("关键词");
        text.append("后".repeat(200));
        appendEvent(a, "user/message", 1, text.toString(), null, null, null);

        List<SessionHit> hits = createIndex(sessionsDir()).search("关键词", 8);
        assertEquals(1, hits.size());
        String snippet = hits.get(0).snippet();
        assertTrue(snippet.contains("【关键词】"), "命中词包裹: " + snippet);
        assertTrue(snippet.startsWith("…") || snippet.length() <= 160,
                "远端命中截断以 … 标注（或窗口内无截断）");
        assertTrue(snippet.length() <= 165, "窗口总量有界（160 + 省略号/括号）: " + snippet.length());
    }

    @Test
    void punctuationOnlyQueryReturnsEmpty() throws IOException {
        Path a = session("20260926-160000-0010");
        appendEvent(a, "user/message", 1, "任意内容", null, null, null);

        assertTrue(createIndex(sessionsDir()).search("！！！？？？", 8).isEmpty(),
                "无有效词元的查询返回空列表");
        assertTrue(createIndex(sessionsDir()).search("", 8).isEmpty(), "空查询返回空列表");
    }

    @Test
    void nonPositiveLimitRejected() throws IOException {
        Path a = session("20260926-160000-0011");
        appendEvent(a, "user/message", 1, "内容", null, null, null);
        SessionQueryService index = createIndex(sessionsDir());

        assertThrows(IllegalArgumentException.class, () -> index.search("内容", 0));
        assertThrows(IllegalArgumentException.class, () -> index.search("内容", -1));
    }

    @Test
    void limitTruncatesResults() throws IOException {
        for (int i = 1; i <= 3; i++) {
            Path f = session(String.format("20260926-160000-00%02d", i));
            appendEvent(f, "user/message", i, "共同关键词第" + i + "条", null, null, null);
        }

        assertEquals(3, createIndex(sessionsDir()).search("共同关键词", 8).size());
        assertEquals(2, createIndex(sessionsDir()).search("共同关键词", 2).size(), "limit 截断");
    }

    @Test
    void badLinesSkippedWithoutFatal() throws IOException {
        Path a = session("20260926-160000-0101");
        Files.writeString(a, "not-a-json-line\n");
        appendEvent(a, "user/message", 2, "好行内容雪莲花", null, null, null);

        List<SessionHit> hits = createIndex(sessionsDir()).search("雪莲花", 8);
        assertEquals(1, hits.size(), "坏行跳过不炸穿搜索");
    }

    @Test
    void titleNotIndexedButCarriedAsMetadata() throws IOException {
        Path a = session("20260926-160000-0102");
        appendEvent(a, "session/title", 1, "标题独有词栀子花", null, null, null);
        appendEvent(a, "user/message", 2, "正文内容风信子", null, null, null);

        assertTrue(createIndex(sessionsDir()).search("栀子花", 8).isEmpty(),
                "标题文本不入检索索引");
        List<SessionHit> hits = createIndex(sessionsDir()).search("风信子", 8);
        assertEquals(1, hits.size());
        assertEquals("标题独有词栀子花", hits.get(0).title(), "标题作呈现元数据随命中返回");
    }

    @Test
    void chunkAndReasoningExcluded() throws IOException {
        Path a = session("20260926-160000-0103");
        appendEvent(a, "assistant/chunk", 1, "流式增量词独角兽", null, null, null);
        appendEvent(a, "tool/call", 2, "{\"path\":\"src/Main.java\"}", "call1", "read",
                "思考内容词葡萄柚");

        assertTrue(createIndex(sessionsDir()).search("独角兽", 8).isEmpty(),
                "chunk 是过程细节不入索引");
        assertTrue(createIndex(sessionsDir()).search("葡萄柚", 8).isEmpty(),
                "reasoning 物理不入索引管线");
        assertEquals(1, createIndex(sessionsDir()).search("Main", 8).size(),
                "tool/call 参数可搜");
        assertEquals(1, createIndex(sessionsDir()).search("read", 8).size(),
                "tool/call 工具名可搜");
    }

    @Test
    void toolResultAndTodoContentIndexed() throws IOException {
        Path a = session("20260926-160000-0104");
        appendEvent(a, "tool/result", 1, "public class Main {}", "call1", "read", null);
        appendEvent(a, "todo/write", 2,
                "[{\"content\":\"实现准入校验\",\"status\":\"completed\"}]", null, null, null);

        assertEquals(1, createIndex(sessionsDir()).search("class", 8).size(), "tool/result 可搜");
        assertEquals(1, createIndex(sessionsDir()).search("准入", 8).size(), "todo content 可搜");
        assertTrue(createIndex(sessionsDir()).search("completed", 8).isEmpty(),
                "todo 状态字段不入（命中清单是噪音）");
    }

    @Test
    void absentDirectoryYieldsEmpty() {
        assertTrue(createIndex(tempDir.resolve("no-such-dir")).search("苹果", 8).isEmpty(),
                "目录缺席（首次启动未建）：空结果不炸");
    }

    @Test
    void concurrentSearchesAreSafe() throws Exception {
        // 线程安全契约（工具侧并发声明依赖）：多线程并发搜索（含首建与增量路径）不炸、结果一致
        Path a = session("20260926-160000-0201");
        appendEvent(a, "user/message", 1, "并发安全的关键词雪莲", null, null, null);
        SessionQueryService index = createIndex(sessionsDir());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 40; i++) {
                futures.add(pool.submit(() -> index.search("雪莲", 8).size()));
            }
            for (var future : futures) {
                assertEquals(1, future.get(10, java.util.concurrent.TimeUnit.SECONDS),
                        "并发搜索结果稳定（线程安全契约）");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
