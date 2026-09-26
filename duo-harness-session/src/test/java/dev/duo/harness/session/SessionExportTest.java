package dev.duo.harness.session;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话导出渲染（M21 工单 09，验收 seam 6；M26 工单 05 增强）：markdown 快照断言 /
 * JSONL 逐行等价 / 屏障（导出含最新消息）/ 交付清单与变更摘要章节 / 流式渲染等价。
 * 夹具 @TempDir 代码生成。
 */
class SessionExportTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SessionExportTest —— 导出渲染：markdown 快照/JSONL 等价/屏障/交付清单与变更摘要章节/流式等价（11 用例） ===");
    }

    private Session sessionWithFixture() throws Exception {
        Session session = Session.create(dir.resolve("sessions"));
        session.append(SessionEvent.title("附件库设计讨论"));
        session.append(SessionEvent.userMessage("帮我看看附件库怎么去重"));
        session.append(SessionEvent.toolCall("call1", "read", "{\"path\":\"src/Main.java\"}"));
        session.append(SessionEvent.toolResult("call1", "read", "public class Main {}"));
        session.append(SessionEvent.assistantMessage("附件库用内容寻址存储。"));
        session.append(SessionEvent.userAttachment(new AttachmentRef(
                "a".repeat(64), "image/png", 1024, "截图.png").toJson()));
        session.append(SessionEvent.userMessage("看下这张图"));
        return session;
    }

    @Test
    void markdownSnapshotContainsRolesToolsAndAttachments() throws Exception {
        Session session = sessionWithFixture();
        String md = SessionExport.markdown(session);
        assertTrue(md.startsWith("# duo 会话导出：附件库设计讨论"));
        assertTrue(md.contains("- 会话 id: `" + session.id() + "`"));
        assertTrue(md.contains("## 用户 · "));       // 角色 + 时间戳头部
        assertTrue(md.contains("## 助手 · "));
        assertTrue(md.contains("### 工具 read · ")); // 工具摘要行（调用名 + 时间戳）
        assertTrue(md.contains("\"path\":\"src/Main.java\"")); // 参数摘要
        assertTrue(md.contains("public class Main {}")); // 结果摘要
        assertTrue(md.contains("## 附件引用清单"));
        assertTrue(md.contains("`" + "a".repeat(64) + "`")); // 尾部清单带 attachmentId
        assertTrue(md.contains("截图.png"));
        assertTrue(md.contains("image/png"));
        assertTrue(md.contains("1024B"));
    }

    @Test
    void jsonlIsLineForLineCopyOfLog() throws Exception {
        Session session = sessionWithFixture();
        session.close();
        // 原样副本：重放读回后逐行等价
        List<String> fileLines = Files.readAllLines(
                dir.resolve("sessions").resolve(session.id() + ".jsonl"), StandardCharsets.UTF_8);
        Session reloaded = Session.load(dir.resolve("sessions").resolve(session.id() + ".jsonl"));
        List<String> exported = List.of(SessionExport.jsonl(reloaded).split("\n", -1));
        assertEquals(fileLines.size(), exported.size() - 1); // 导出末尾多一个换行的空串
        for (int i = 0; i < fileLines.size(); i++) {
            assertEquals(fileLines.get(i), exported.get(i), "第 " + i + " 行不等价");
        }
        reloaded.close();
    }

    @Test
    void exportSeesLatestAppend() throws Exception {
        Session session = sessionWithFixture();
        // 屏障语义：导出前一刻追加的消息必须在导出里（快照即持久化视图）
        session.append(SessionEvent.userMessage("屏障检查的最新消息"));
        String md = SessionExport.markdown(session);
        assertTrue(md.contains("屏障检查的最新消息"));
        assertTrue(SessionExport.jsonl(session).contains("屏障检查的最新消息"));
        session.close();
    }

    @Test
    void formatParseDefaultsAndRejects() {
        assertEquals(SessionExport.Format.MARKDOWN, SessionExport.Format.parse(""));
        assertEquals(SessionExport.Format.MARKDOWN, SessionExport.Format.parse(null));
        assertEquals(SessionExport.Format.MARKDOWN, SessionExport.Format.parse("markdown"));
        assertEquals(SessionExport.Format.JSON, SessionExport.Format.parse("JSON")); // 大小写宽容
        assertEquals(null, SessionExport.Format.parse("xml"));
    }

    @Test
    void fileNameFollowsConvention() {
        assertEquals("duo-session-20260920-100000-0001.md",
                SessionExport.fileName("20260920-100000-0001", SessionExport.Format.MARKDOWN));
        assertEquals("duo-session-20260920-100000-0001.jsonl",
                SessionExport.fileName("20260920-100000-0001", SessionExport.Format.JSON));
    }

    // ===== M26 工单 05：交付清单 / 变更摘要 / 流式 =====

    @Test
    void deliverablesChapterAggregatesDeclarations() throws Exception {
        // 交付清单章节：多次声明去重保序；无声明省略章节
        Session session = sessionWithFixture();
        session.append(SessionEvent.deliverablePresented(
                "[\"/tmp/p/out/报告-v2.md\", \"/tmp/p/data.json\"]"));
        session.append(SessionEvent.deliverablePresented("[\"/tmp/p/out/报告-v2.md\"]"));
        String md = SessionExport.markdown(session);
        assertTrue(md.contains("## 交付清单（模型声明）"), md);
        assertTrue(md.contains("- `/tmp/p/out/报告-v2.md`"), "跨声明去重后仍列出");
        assertTrue(md.contains("- `/tmp/p/data.json`"));

        Session noDeliverable = sessionWithFixture();
        assertFalse(SessionExport.markdown(noDeliverable).contains("## 交付清单"),
                "无声明的会话省略章节");
    }

    @Test
    void changeSummaryChapterRendersBothForms() throws Exception {
        Session session = sessionWithFixture();
        // git 对账形态：行数表 + 工具触碰注记
        var report = new SessionExport.ChangeReport(true,
                List.of(new SessionExport.ChangeRow("src/A.java", "12", "3"),
                        new SessionExport.ChangeRow("logo.png", null, null)),
                List.of("src/A.java"));
        String md = SessionExport.markdown(session, report);
        assertTrue(md.contains("## 变更摘要（系统对账）"));
        assertTrue(md.contains("| `src/A.java` | 12 | 3 |"), md);
        assertTrue(md.contains("| `logo.png` | (binary) | (binary) |"), "二进制行显示标记");
        assertTrue(md.contains("另经文件工具触碰"));

        // 退化形态：仅工具记录（无行数）
        var fallback = new SessionExport.ChangeReport(false, List.of(),
                List.of("src/A.java", "src/B.java"));
        String md2 = SessionExport.markdown(session, fallback);
        assertTrue(md2.contains("非 git 目录或快照不可用——仅文件工具记录，无行数统计"));
        assertTrue(md2.contains("- `src/B.java`"));

        // 空报告：无文件变更
        String md3 = SessionExport.markdown(session, SessionExport.ChangeReport.NONE);
        assertTrue(md3.contains("## 变更摘要（系统对账）"));
        assertTrue(md3.contains("无文件变更"));
    }

    @Test
    void streamingRendersEqualToStringForms() throws Exception {
        // 流式核心与字符串形态逐字等价（CLI 写盘 / Web 下载与既有形态不漂移）
        Session session = sessionWithFixture();
        session.append(SessionEvent.deliverablePresented("[\"/tmp/p/out/报告.md\"]"));
        var report = new SessionExport.ChangeReport(true,
                List.of(new SessionExport.ChangeRow("src/A.java", "1", "0")), List.of());

        StringBuilder streamed = new StringBuilder();
        SessionExport.renderMarkdown(session, report, streamed);
        assertEquals(SessionExport.markdown(session, report), streamed.toString());

        StringBuilder jsonlStreamed = new StringBuilder();
        SessionExport.renderJsonl(session, jsonlStreamed);
        assertEquals(SessionExport.jsonl(session), jsonlStreamed.toString());
        // 含头行（M26-01）：逐行等价磁盘
        assertEquals(Files.readAllLines(session.jsonl()), session.jsonlLines());
    }

}