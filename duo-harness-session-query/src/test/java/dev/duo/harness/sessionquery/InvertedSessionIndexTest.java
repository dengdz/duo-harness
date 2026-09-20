package dev.duo.harness.sessionquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSONL 夹具检索全链（工单 08 验收 seam 5）：命中/事件定位/snippet/懒构建/
 * mtime 增量/分词 AND/reasoning 不入/子代理会话不索引/上下文边界。夹具全部
 * 代码生成，零真实会话文件。
 */
class InvertedSessionIndexTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    // ---- 夹具写入 ----

    /** 写一条事件行（可选字段按存在写入，与会话层 JSONL 形态一致）。 */
    private void appendEvent(Path jsonl, String type, long at, String text,
                             String toolCallId, String toolName, String reasoning) throws Exception {
        ObjectNode node = JSON.createObjectNode()
                .put("type", type).put("at", at).put("text", text);
        if (toolCallId != null) {
            node.put("toolCallId", toolCallId);
        }
        if (toolName != null) {
            node.put("toolName", toolName);
        }
        if (reasoning != null) {
            node.put("reasoning", reasoning);
        }
        Files.writeString(jsonl, JSON.writeValueAsString(node) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void writeMainFixture() throws Exception {
        Path jsonl = dir.resolve("20260919-100000-0001.jsonl");
        appendEvent(jsonl, "session/title", 1, "标题独有词栀子花", null, null, null);
        appendEvent(jsonl, "user/message", 2, "帮我看看附件库的硬链接去重怎么实现", null, null, null);
        appendEvent(jsonl, "assistant/message", 3, "附件库用内容寻址存储，配置解析在 config parser 里。",
                null, null, null);
        // reasoning 字段带独有词"葡萄柚"——物理不入索引（IndexedEvent 无此字段），用例钉住
        appendEvent(jsonl, "tool/call", 4, "{\"path\":\"src/Main.java\"}", "call1", "read",
                "内部思考词葡萄柚不应被检索");
        appendEvent(jsonl, "tool/result", 5, "public class Main {}", "call1", "read", null);
        appendEvent(jsonl, "todo/write", 6,
                "[{\"content\":\"实现准入校验\",\"status\":\"completed\"},"
                        + "{\"content\":\"写规范化管线\",\"status\":\"in_progress\"}]", null, null, null);
        // assistant/chunk 与 run/error 都不是投影消息——chunk 不入（词"独角兽"），turn 错误入（词"银河系"）
        appendEvent(jsonl, "assistant/chunk", 7, "流式增量词独角兽不应入索引", null, null, null);
        appendEvent(jsonl, "run/error", 8, "上游连接中断词银河系", null, null, null);
    }

    // ---- 命中与出处 ----

    @Test
    void searchFindsHitWithEventIndexAndTitle() throws Exception {
        writeMainFixture();
        List<SessionHit> hits = new InvertedSessionIndex(dir).search("硬链接", 8);
        assertEquals(1, hits.size());
        SessionHit hit = hits.get(0);
        assertEquals("20260919-100000-0001", hit.sessionId());
        assertEquals("标题独有词栀子花", hit.title()); // 标题作呈现元数据随命中返回
        assertEquals(1, hit.eventIndex()); // user/message 是第 2 行（0 起）
        assertEquals("user/message", hit.eventType());
        assertTrue(hit.snippet().contains("【硬链接】"));
        assertTrue(hit.score() >= 1);
    }

    @Test
    void toolCallMatchesByToolNameAndArgs() throws Exception {
        writeMainFixture();
        InvertedSessionIndex index = new InvertedSessionIndex(dir);
        assertEquals("tool/call", index.search("Main.java", 8).get(0).eventType());
        assertEquals("tool/call", index.search("read", 8).get(0).eventType());
    }

    @Test
    void todoContentIndexedStatusNot() throws Exception {
        writeMainFixture();
        InvertedSessionIndex index = new InvertedSessionIndex(dir);
        List<SessionHit> hits = index.search("准入校验", 8);
        assertEquals(1, hits.size());
        assertEquals("todo/write", hits.get(0).eventType());
        assertTrue(index.search("completed", 8).isEmpty()); // 状态字段不入——命中是噪音
    }

    @Test
    void turnErrorIndexedButChunkNot() throws Exception {
        writeMainFixture();
        InvertedSessionIndex index = new InvertedSessionIndex(dir);
        List<SessionHit> runErrors = index.search("银河系", 8);
        assertEquals(1, runErrors.size());
        assertEquals("run/error", runErrors.get(0).eventType());
        assertTrue(index.search("独角兽", 8).isEmpty()); // chunk 是过程细节，与完整消息重复
    }

    @Test
    void reasoningNeverIndexed() throws Exception {
        writeMainFixture();
        assertTrue(new InvertedSessionIndex(dir).search("葡萄柚", 8).isEmpty());
    }

    @Test
    void titleTextNotIndexed() throws Exception {
        writeMainFixture();
        assertTrue(new InvertedSessionIndex(dir).search("栀子花", 8).isEmpty());
    }

    // ---- 子代理会话不索引 ----

    @Test
    void subagentSessionsNotIndexed() throws Exception {
        writeMainFixture();
        Path subagents = dir.resolve("subagents");
        Files.createDirectories(subagents);
        appendEvent(subagents.resolve("child.jsonl"), "assistant/message", 9,
                "子代理会话独有词仙人掌", null, null, null);
        assertTrue(new InvertedSessionIndex(dir).search("仙人掌", 8).isEmpty());
        assertEquals(1, new InvertedSessionIndex(dir).search("硬链接", 8).size()); // 主会话照常
    }

    // ---- 分词 AND 与词形 ----

    @Test
    void andSemanticsAllTokensRequired() throws Exception {
        writeMainFixture();
        InvertedSessionIndex index = new InvertedSessionIndex(dir);
        assertEquals(1, index.search("附件 硬链接", 8).size()); // 两词同句——命中
        assertTrue(index.search("附件 银河系", 8).isEmpty()); // 分处两事件——同事件 AND 不成立
    }

    @Test
    void englishWordMatchingIsWholeWordCaseInsensitive() throws Exception {
        writeMainFixture();
        InvertedSessionIndex index = new InvertedSessionIndex(dir);
        assertEquals(1, index.search("PARSER", 8).size()); // 大小写不敏感
        assertTrue(index.search("pars", 8).isEmpty()); // 整词边界——子串不命中
    }

    // ---- 懒构建与增量 ----

    @Test
    void lazyBuildPicksUpFilesCreatedAfterConstruction() throws Exception {
        InvertedSessionIndex index = new InvertedSessionIndex(dir); // 目录尚不存在
        assertTrue(index.search("硬链接", 8).isEmpty());
        writeMainFixture(); // 首次搜索才扫——此后新建的文件下次搜索自然收录
        List<SessionHit> hits = index.search("硬链接", 8);
        assertEquals(1, hits.size());
    }

    @Test
    void mtimeIncrementPicksUpAppends() throws Exception {
        Path jsonl = dir.resolve("20260919-100000-0001.jsonl");
        appendEvent(jsonl, "user/message", 1, "初始词甲的讨论", null, null, null);
        InvertedSessionIndex index = new InvertedSessionIndex(dir);
        assertEquals(1, index.search("初始词甲", 8).size());
        assertTrue(index.search("增量词乙", 8).isEmpty());

        appendEvent(jsonl, "assistant/message", 2, "后续追加了增量词乙的内容", null, null, null);
        Files.setLastModifiedTime(jsonl, FileTime.fromMillis(
                Files.getLastModifiedTime(jsonl).toMillis() + 60_000)); // 戳必变：append 改 size，此处双保险
        List<SessionHit> hits = index.search("增量词乙", 8);
        assertEquals(1, hits.size());
        assertEquals(1, hits.get(0).eventIndex()); // 新事件按行号定位
        assertEquals(1, index.search("初始词甲", 8).size()); // 旧内容仍在索引
    }

    @Test
    void deletedFilesDropOutOfIndex() throws Exception {
        Path jsonl = dir.resolve("20260919-100000-0001.jsonl");
        appendEvent(jsonl, "user/message", 1, "苹果的讨论", null, null, null);
        InvertedSessionIndex index = new InvertedSessionIndex(dir);
        assertEquals(1, index.search("苹果", 8).size());
        Files.delete(jsonl);
        assertTrue(index.search("苹果", 8).isEmpty());
    }

    // ---- 排序、截断与边界 ----

    @Test
    void rankingByMatchStrengthThenRecency() throws Exception {
        appendEvent(dir.resolve("a.jsonl"), "user/message", 1, "苹果", null, null, null);
        appendEvent(dir.resolve("b.jsonl"), "user/message", 2, "苹果 苹果", null, null, null);
        List<SessionHit> hits = new InvertedSessionIndex(dir).search("苹果", 8);
        assertEquals(2, hits.size());
        assertEquals("b", hits.get(0).sessionId()); // 词频高者强
        // 短语主键（苹果 ×2 = 2000）+ 词频次键（苹×2 + 果×2 = 4）——中文单字分词各计一次
        assertEquals(2004, hits.get(0).score());
    }

    @Test
    void limitCapsResults() throws Exception {
        for (int i = 0; i < 5; i++) {
            appendEvent(dir.resolve("s" + i + ".jsonl"), "user/message", i, "苹果", null, null, null);
        }
        assertEquals(3, new InvertedSessionIndex(dir).search("苹果", 3).size());
    }

    @Test
    void snippetWindowBoundedAndMarked() throws Exception {
        String filler = "字".repeat(300);
        appendEvent(dir.resolve("a.jsonl"), "user/message", 1,
                filler + "这里有关键词苹果在很后面", null, null, null);
        SessionHit hit = new InvertedSessionIndex(dir).search("苹果", 8).get(0);
        assertTrue(hit.snippet().startsWith("…")); // 命中在深处：窗口前截断
        assertTrue(hit.snippet().contains("【苹果】"));
        assertTrue(hit.snippet().length() <= InvertedSessionIndex.SNIPPET_TOTAL + 4);
    }

    @Test
    void activeSessionHeldByThisProcessSkippedAndLockUntouched() throws Exception {
        // P1 回归：索引扫描绝不触碰本进程持独占锁的活跃会话文件——开读再关 fd
        // 会按 POSIX 语义释放属主锁（review-log M19 模式①，双轴审查 P1）
        Session live = Session.create(dir);
        live.append(SessionEvent.userMessage("活跃会话独有词柚子"));
        Path held = dir.resolve(live.id() + ".jsonl");
        assertTrue(dev.duo.harness.session.Session.heldByThisProcess(held));

        new InvertedSessionIndex(dir).search("柚子", 8); // 扫描发生
        assertTrue(new InvertedSessionIndex(dir).search("柚子", 8).isEmpty(),
                "活跃会话不入索引（跳过）");

        // OS 级断言：扫描后属主锁仍在——另一 channel tryLock 必须撞 JDK 重叠锁异常
        try (var probe = java.nio.channels.FileChannel.open(held,
                java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.nio.channels.OverlappingFileLockException.class, probe::tryLock,
                    "扫描关闭读取 fd 不得释放属主锁（POSIX 释放陷阱）");
        }
        live.close(); // 释放后同一文件可入索引
        assertEquals(1, new InvertedSessionIndex(dir).search("柚子", 8).size());
    }

    @Test
    void blankQueryAndMissingDirReturnEmpty() {
        InvertedSessionIndex index = new InvertedSessionIndex(dir.resolve("不存在"));
        assertTrue(index.search("苹果", 8).isEmpty());
        assertTrue(index.search("   ", 8).isEmpty());
        assertTrue(index.search(null, 8).isEmpty());
    }

    @Test
    void nonPositiveLimitRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new InvertedSessionIndex(dir).search("苹果", 0));
    }

    @Test
    void corruptLinesSkippedNotFatal() throws Exception {
        Path jsonl = dir.resolve("a.jsonl");
        Files.writeString(jsonl, "{这不是JSON}\n", StandardCharsets.UTF_8);
        appendEvent(jsonl, "user/message", 1, "苹果的讨论", null, null, null);
        List<SessionHit> hits = new InvertedSessionIndex(dir).search("苹果", 8);
        assertEquals(1, hits.size());
        assertEquals(1, hits.get(0).eventIndex()); // 坏行占位 0，好行按行号定位
    }

    @Test
    void eventWithoutTimestampStillParses() throws Exception {
        Path jsonl = dir.resolve("a.jsonl");
        Files.writeString(jsonl,
                "{\"type\":\"user/message\",\"text\":\"无时间戳的苹果\"}\n", StandardCharsets.UTF_8);
        SessionHit hit = new InvertedSessionIndex(dir).search("苹果", 8).get(0);
        assertEquals(0, hit.eventAt()); // 缺省字段按 0 读——旧格式/手写夹具不炸
    }

    @Test
    void titleAbsentIsNull() throws Exception {
        appendEvent(dir.resolve("a.jsonl"), "user/message", 1, "苹果", null, null, null);
        assertNull(new InvertedSessionIndex(dir).search("苹果", 8).get(0).title());
    }

    @Test
    void snippetWithoutMatchFallbackIsHead() {
        assertEquals("短文本", InvertedSessionIndex.snippet("短文本", List.of("不", "在"), List.of()));
    }

    @Test
    void phraseOccurrencesOutrankScatteredChars() throws Exception {
        // a 真说过"附件库"；b 只有附/件/库三字散落（单字 AND 仍命中，但短语加分应压过词频噪音）
        appendEvent(dir.resolve("a.jsonl"), "user/message", 1,
                "咱们之前讨论过附件库的准入语义", null, null, null);
        appendEvent(dir.resolve("b.jsonl"), "tool/result", 2,
                "附在心里的零件清点完毕后全部移交库房保存" + "零件库房".repeat(20), null, null, null);
        List<SessionHit> hits = new InvertedSessionIndex(dir).search("附件库", 8);
        assertEquals(2, hits.size());
        assertEquals("a", hits.get(0).sessionId());
        assertTrue(hits.get(0).snippet().contains("【附件库】")); // 锚在原词处，不是散字噪音
    }

    @Test
    void phraseBonusBreaksTieTowardRealPhrase() throws Exception {
        // 两事件同字符数（词频同分），含原词者胜
        appendEvent(dir.resolve("a.jsonl"), "user/message", 1, "附件库", null, null, null);
        appendEvent(dir.resolve("b.jsonl"), "user/message", 2, "附了件入库", null, null, null);
        List<SessionHit> hits = new InvertedSessionIndex(dir).search("附件库", 8);
        assertEquals("a", hits.get(0).sessionId());
    }

    @Test
    void cjkRunsExtractsContiguousSequences() {
        // 汉字连续串即短语（"与/和"也是汉字不断开）；分隔符用非汉字字符
        assertEquals(List.of("附件库", "硬链接"), InvertedSessionIndex.cjkRuns("附件库/硬链接"));
        assertTrue(InvertedSessionIndex.cjkRuns("苹果 苹果").contains("苹果"));
        assertTrue(InvertedSessionIndex.cjkRuns("config parser").isEmpty()); // 纯英文无短语概念
    }

    @Test
    void wordBoundaryPreventsSubstringHits() throws Exception {
        appendEvent(dir.resolve("a.jsonl"), "user/message", 1,
                "pineapple 与苹果无关", null, null, null);
        assertTrue(new InvertedSessionIndex(dir).search("apple", 8).isEmpty()); // 整词边界
    }
}
