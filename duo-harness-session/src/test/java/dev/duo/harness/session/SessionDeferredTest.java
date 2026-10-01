package dev.duo.harness.session;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.duo.harness.core.api.PluginException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话持久化 defer 化用例（M30 工单 05）：创建仅内存态、首条真实事件才落盘
 * （版本头与首事件同批写，零头-only 窗口）、列表天然干净 + 头-only 防御过滤。
 * ZCode deferred/draft 对齐——空会话既不堆积也不可见。
 */
class SessionDeferredTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SessionDeferredTest —— deferred 创建零文件、首事件头+事件同批落盘、"
                + "close 消失、列表头-only 过滤（6 用例） ===");
    }

    @TempDir
    Path tempDir;

    @Test
    void deferredCreateTouchesNothingOnDisk() throws Exception {
        Path sessionsDir = tempDir.resolve("agent-sessions");
        Session session = Session.createDeferred(sessionsDir);

        assertFalse(Files.exists(sessionsDir), "deferred 创建不得创建目录");
        assertEquals(0, Session.list(sessionsDir).size(), "deferred 会话不可见于列表");
        assertFalse(Session.heldByThisProcess(session.jsonl()), "deferred 会话不持文件锁");

        session.close();
        assertFalse(Files.exists(sessionsDir), "close 后依旧零文件（重启即消失语义）");
    }

    @Test
    void firstAppendMaterializesHeaderAndEventInOneBatch() throws Exception {
        Path sessionsDir = tempDir.resolve("agent-sessions");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(cwd);
        Session session = Session.createDeferred(sessionsDir, cwd);

        session.append(SessionEvent.userMessage("第一条真实消息"));

        Path jsonl = session.jsonl();
        assertTrue(Files.isRegularFile(jsonl), "首条事件落盘后文件出现");
        String content = Files.readString(jsonl);
        String[] lines = content.split("\n");
        assertTrue(lines.length >= 2, "头 + 首事件应同批落盘（至少两行）: " + content);
        assertTrue(SessionFormat.isHeaderLine(lines[0]), "首行必须是版本头（同批写，无头-only 窗口）");
        assertTrue(lines[1].contains("user/message"), "第二行即首事件");
        assertEquals(SessionFormat.CURRENT_VERSION, session.formatVersion(), "物化后格式版本为当前版本");
        assertEquals(cwd, session.cwd(), "cwd 在 deferred 期保留并随版本头落盘");
        assertTrue(Session.heldByThisProcess(jsonl), "物化后取得文件锁");

        session.close(); // 持锁实例须先释放再重放（HELD_LOCKS 拦同进程第二实例）
        Session reopened = Session.load(jsonl);
        assertEquals(1, reopened.events().size(), "物化文件可正常重放");
        assertEquals("第一条真实消息", reopened.events().get(0).text());
        reopened.close();
    }

    @Test
    void secondAppendGoesStraightToDisk() throws Exception {
        Path sessionsDir = tempDir.resolve("agent-sessions");
        Session session = Session.createDeferred(sessionsDir);
        session.append(SessionEvent.userMessage("第一条"));
        long sizeAfterFirst = Files.size(session.jsonl());
        session.append(SessionEvent.assistantMessage("第二条"));
        assertTrue(Files.size(session.jsonl()) > sizeAfterFirst, "后续事件正常追加");
        session.close();
        assertEquals(2, Session.load(session.jsonl()).events().size());
    }

    @Test
    void deferredSessionWithNoEventsLeavesNoFileBehind() {
        Path sessionsDir = tempDir.resolve("agent-sessions");
        Session session = Session.createDeferred(sessionsDir);
        session.addListener((index, event) -> { /* SSE 订阅在 deferred 期合法 */ });
        session.close();
        assertFalse(Files.exists(sessionsDir));
    }

    @Test
    void listSkipsEmptyAndHeaderOnlyFiles() throws Exception {
        Path sessionsDir = tempDir.resolve("agent-sessions");
        Files.createDirectories(sessionsDir);

        Path empty = sessionsDir.resolve("20260101-000001-0001.jsonl");
        Files.createFile(empty); // 0 字节（历史遗留形态一）

        Path headerOnly = sessionsDir.resolve("20260101-000002-0002.jsonl");
        Files.writeString(headerOnly, SessionFormat.headerLine(SessionFormat.CURRENT_VERSION, null) + "\n");
        // 头-only（历史遗留形态二：79B 版本头文件）

        Session real = Session.create(sessionsDir, null);
        real.append(SessionEvent.userMessage("真实会话"));
        real.close();

        List<Session.SessionSummary> summaries = Session.list(sessionsDir);
        assertEquals(1, summaries.size(), "0 字节与头-only 文件应被防御过滤");
        assertEquals(real.id(), summaries.get(0).id());

        Session latest = Session.latest(sessionsDir);
        assertEquals(real.id(), latest.id(), "latest 不被头-only 文件劫持（BUG-20260923-01 语义加强）");
        latest.close();
    }

    @Test
    void deferredAndLegacyCreateCoexist() throws Exception {
        Path sessionsDir = tempDir.resolve("agent-sessions");
        Session legacy = Session.create(sessionsDir, null); // 立即落盘形态（headless/子代理仍用）
        Session deferred = Session.createDeferred(sessionsDir);

        legacy.append(SessionEvent.userMessage("legacy 首条"));
        deferred.append(SessionEvent.userMessage("deferred 首条"));

        // legacy 落盘 + deferred 已物化 = 两条真实会话均可见
        assertEquals(2, Session.list(sessionsDir).size());
        legacy.close();
        deferred.close();
        assertEquals(1, Session.load(legacy.jsonl()).events().size());
        assertEquals(1, Session.load(deferred.jsonl()).events().size());
    }

    @Test
    void materializeFailureKeepsDeferredStateAndRetrySucceeds() throws Exception {
        Path parent = tempDir.resolve("locked-parent");
        Files.createDirectories(parent);
        Path restricted = parent.resolve("sessions");
        Session session = Session.createDeferred(restricted);

        // 物化失败（父目录去写权限）：异常上抛、实例保持 deferred、不留半成品
        Files.setPosixFilePermissions(parent, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        assertThrows(PluginException.class, () -> session.append(SessionEvent.userMessage("会失败的首条")));
        assertFalse(Files.exists(restricted), "物化失败不留半成品目录/文件");

        // 恢复权限后重试：ensureLocked 幂等（首次失败未取得锁）、头+事件同批落盘成功
        Files.setPosixFilePermissions(parent, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        session.append(SessionEvent.userMessage("重试成功的首条"));
        assertTrue(SessionFormat.isHeaderLine(Files.readString(session.jsonl()).split("\n")[0]),
                "重试物化仍同批写版本头");
        assertEquals(1, session.events().size());
        session.close();
    }
}
