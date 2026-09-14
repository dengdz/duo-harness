package dev.duo.harness.session;

import dev.duo.harness.core.api.PluginException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话事件溯源用例：append 单写（内存 + JSONL 同步落盘）、重放读回一致、
 * 投影规则（message 入列 / chunk 不投影）、空会话、latest 选取。
 */
class SessionTest {

    @TempDir
    Path tempDir;

    private Path sessionsDir() {
        return tempDir.resolve("sessions");
    }

    /** 组装一轮完整对话（用户消息 + 两段 chunk + 完整消息）。 */
    private static void appendRound(Session session, String userText, String assistantText) {
        session.append(SessionEvent.userMessage(userText));
        session.append(SessionEvent.assistantChunk(assistantText + "-1"));
        session.append(SessionEvent.assistantChunk(assistantText + "-2"));
        session.append(SessionEvent.assistantMessage(assistantText));
    }

    @Test
    void appendWritesMemoryAndJsonlTogether() throws IOException {
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.userMessage("你好"));

        // 内存观测量：事件可见
        assertEquals(1, session.events().size());
        assertEquals(SessionEvent.USER_MESSAGE, session.events().get(0).type());
        // 磁盘观测量：JSONL 逐行落盘
        List<String> lines = Files.readAllLines(session.jsonl());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"type\":\"user/message\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"text\":\"你好\""), lines.get(0));
    }

    @Test
    void loadReplaysIdenticalSequence() {
        Session original = Session.create(sessionsDir());
        appendRound(original, "第一问", "第一答");
        appendRound(original, "第二问", "第二答");

        // 单写者语义：会话持有独占锁——同一文件重开需先关闭原属主
        original.close();
        Session replayed = Session.load(original.jsonl());

        assertEquals(original.id(), replayed.id());
        assertEquals(original.events(), replayed.events(), "读回事件序列应与写入一致");
    }

    @Test
    void deriveMessagesProjectsMessagesOnly() {
        Session session = Session.create(sessionsDir());
        appendRound(session, "第一问", "第一答");
        session.append(SessionEvent.userMessage("第二问"));
        session.append(SessionEvent.assistantChunk("过程细节"));

        List<Message> messages = session.deriveMessages();

        assertEquals(3, messages.size(), "chunk 不投影: " + messages);
        assertEquals(Message.Role.USER, messages.get(0).role());
        assertEquals("第一问", messages.get(0).content());
        assertEquals(Message.Role.ASSISTANT, messages.get(1).role());
        assertEquals("第一答", messages.get(1).content());
        assertEquals(Message.Role.USER, messages.get(2).role());
    }

    @Test
    void emptySessionDerivesEmptyProjection() {
        Session session = Session.create(sessionsDir());

        assertTrue(session.deriveMessages().isEmpty(), "空会话投影应为空列表");
        assertTrue(session.events().isEmpty());
    }

    @Test
    void textPayloadWithQuotesAndNewlineSurvivesRoundTrip() {
        Session original = Session.create(sessionsDir());
        String tricky = "带\"引号\"与\n换行\t的文本";
        original.append(SessionEvent.userMessage(tricky));

        // 单写者语义：会话持有独占锁——同一文件重开需先关闭原属主
        original.close();
        Session replayed = Session.load(original.jsonl());

        assertEquals(tricky, replayed.events().get(0).text(), "特殊字符载荷应原样回放");
    }

    @Test
    void latestPicksMostRecentlyModified() throws IOException {
        // 手工控制文件名：绕开 id 生成的随机性，确定性验证"字典序最大 = 最新"
        Files.createDirectories(sessionsDir());
        Path older = sessionsDir().resolve("20260101-000000-aaaa.jsonl");
        Path newer = sessionsDir().resolve("20260101-000000-bbbb.jsonl");
        Files.writeString(older, "");
        Files.writeString(newer, "");
        // 修改时间粒度可能同毫秒：显式错开，保证"最近活动"判定确定
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(older).toMillis() + 10_000));

        Session latest = Session.latest(sessionsDir());

        assertNotNull(latest);
        assertEquals("20260101-000000-bbbb", latest.id(), "应取修改时间最新的会话");
    }

    @Test
    void latestReturnsNullWhenDirectoryEmpty() {
        assertNull(Session.latest(sessionsDir()), "空目录应返回 null");
    }

    @Test
    void legacyToolEventsWithoutIdAreSkippedInProjection() throws IOException {
        // 手写旧格式 JSONL（无 toolCallId/toolName——BUG-20260912-04 的触发形态）
        Files.createDirectories(sessionsDir());
        Path legacy = sessionsDir().resolve("20260101-000000-legacy.jsonl");
        Files.writeString(legacy, "{\"type\":\"user/message\",\"at\":1,\"text\":\"旧会话\"}\n"
                + "{\"type\":\"tool/call\",\"at\":2,\"text\":\"read_file {}\"}\n"
                + "{\"type\":\"tool/result\",\"at\":3,\"text\":\"结果\"}\n");

        Session replayed = Session.load(legacy);
        List<Message> messages = replayed.deriveMessages();

        assertEquals(1, messages.size(), "旧格式工具事件跳过，仅 user 消息投影");
        assertEquals(Message.Role.USER, messages.get(0).role());
    }

    @Test
    void usageRoundTripsOnAssistantMessage() throws IOException {
        // ADR-0009：真实 token 用量作为 assistant/message 可选字段随 JSONL 持久化
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.userMessage("问"));
        session.append(SessionEvent.assistantMessage("答", new TokenUsage(1200, 340, 1540)));

        // 单写者语义：会话持有独占锁——同一文件重开需先关闭原属主
        session.close();
        Session reloaded = Session.load(session.jsonl());
        assertEquals(new TokenUsage(1200, 340, 1540), reloaded.events().get(1).usage(),
                "usage 随 JSONL 完整往返");
        assertEquals("答", reloaded.events().get(1).text());
        assertNull(reloaded.events().get(0).usage(), "user 消息不携带 usage");
    }

    @Test
    void legacyAssistantMessageWithoutUsageLoadsAsNull() throws IOException {
        Files.createDirectories(sessionsDir());
        Path legacy = sessionsDir().resolve("20260101-000000-nousage.jsonl");
        Files.writeString(legacy, "{\"type\":\"assistant/message\",\"at\":1,\"text\":\"旧回复\"}\n");

        Session replayed = Session.load(legacy);
        assertNull(replayed.events().get(0).usage(), "旧格式无 usage 字段落 null（向后兼容）");
        assertEquals(1, replayed.deriveMessages().size(), "旧格式照常投影");
    }

    @Test
    void secondInstanceOnSameFileIsRejected() throws IOException {
        // 单写者检测（工单 M10-03）：同一会话被第二实例打开即报错——两个内存视图
        // 各写各的会让 JSONL 交错追加、上下文静默分叉
        Session owner = Session.create(sessionsDir());
        owner.append(SessionEvent.userMessage("属主写入"));

        SessionLockedException rejected = assertThrows(SessionLockedException.class,
                () -> Session.load(owner.jsonl()), "同进程第二实例打开同一会话应被拒");
        assertEquals(owner.id(), rejected.sessionId(), "异常点名被占会话");

        owner.append(SessionEvent.assistantMessage("属主继续写"));
        assertEquals(2, owner.events().size(), "属主不受他人打开失败影响");
        owner.close();
    }

    @Test
    void latestRejectsOccupiedSession() throws IOException {
        // 续接路径（CLI 与 Web 启动都经 latest）：会话被占时打开即失败——报错点即用户决策点
        Session owner = Session.create(sessionsDir());
        owner.append(SessionEvent.userMessage("占着最新位置"));

        SessionLockedException rejected = assertThrows(SessionLockedException.class,
                () -> Session.latest(sessionsDir()));
        assertEquals(owner.id(), rejected.sessionId());
        owner.close();
    }

    @Test
    void closeReleasesLockForReopenAndIsIdempotent() throws IOException {
        // 释放语义：close 后同一文件可被重新打开（换绑、进程退出的正常路径）；重复 close 无副作用
        Session first = Session.create(sessionsDir());
        first.append(SessionEvent.userMessage("一"));
        Path file = first.jsonl();
        first.close();
        first.close();

        Session reopened = Session.load(file);
        assertEquals(1, reopened.events().size(), "重开后事件完整");
        reopened.close();
        assertNotNull(Session.latest(sessionsDir()), "latest 可正常打开（锁已释放）");
    }

    @Test
    void appendAfterCloseRejected() throws IOException {
        // 契约闭环（双轴审查）：关闭后写入属调用方错误——锁已释放，
        // 继续写会与可能接手的新属主形成无锁并发
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.userMessage("关闭前"));
        session.close();

        assertThrows(IllegalStateException.class,
                () -> session.append(SessionEvent.userMessage("关闭后")));
    }

    @Test
    void loadRejectsCorruptLine() throws IOException {
        Path file = sessionsDir().resolve("bad.jsonl");
        Files.createDirectories(sessionsDir());
        Files.writeString(file, "{不是JSON");

        assertThrows(PluginException.class, () -> Session.load(file));
    }

    @Test
    void toolCallReasoningRoundTripsAndProjectsBack() throws IOException {
        // M6 BUG-20260913-03 修复：tool/call 事件的 reasoning 随 JSONL 持久化，
        // 投影重建的 assistant 消息携带思考内容（provider 要求回传）
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.toolCall("call_1", "read_file", "{}", "第一轮思考"));
        session.append(SessionEvent.toolResult("call_1", "read_file", "内容"));

        // 单写者语义：会话持有独占锁——同一文件重开需先关闭原属主
        session.close();
        Session reloaded = Session.load(session.jsonl());
        assertEquals("第一轮思考", reloaded.events().get(0).reasoning(), "reasoning 随 JSONL 往返");
        assertEquals("第一轮思考", reloaded.deriveMessages().get(0).reasoning(),
                "投影出的 assistant 消息携带思考内容");
    }

    @Test
    void listenerReceivesAppendsWithLogIndexAndRemovableStopsDelivery() throws Exception {
        // M8 事件流推送源 + M10 游标锚点：append 成功后监听器同步收到（序号, 事件）；
        // 序号是重连游标（SSE Last-Event-ID）的锚点，注销器生效
        Session session = Session.create(sessionsDir());
        List<String> received = new java.util.ArrayList<>();
        List<Integer> indexes = new java.util.ArrayList<>();
        dev.duo.harness.core.api.Disposable removal = session.addListener((index, event) -> {
            indexes.add(index);
            received.add(event.text());
        });

        session.append(SessionEvent.userMessage("第一条"));
        session.append(SessionEvent.userMessage("第二条"));
        removal.dispose();
        session.append(SessionEvent.userMessage("注销后"));

        assertEquals(2, received.size(), "注销前两条均送达");
        assertEquals("第一条", received.get(0));
        assertEquals("第二条", received.get(1));
        assertEquals(List.of(0, 1), indexes, "回调携带日志序号（append-only 列表下标）");
        assertEquals(3, session.events().size(), "注销不影响事件落盘");
        assertEquals("注销后", session.events().get(2).text());
    }

    @Test
    void eventsSnapshotStableUnderAppend() {
        // events() 是调用时刻的快照：追加不影响既有快照的遍历（Web SSE 回放与
        // agent 流式追加并发的根防御——活视图会在遍历中抛 ConcurrentModificationException）
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.userMessage("快照前"));
        List<SessionEvent> snapshot = session.events();
        java.util.Iterator<SessionEvent> iterator = snapshot.iterator();
        session.append(SessionEvent.userMessage("快照后追加"));

        assertEquals(1, snapshot.size(), "快照不受后续追加影响");
        assertEquals("快照前", iterator.next().text(), "取快照时的迭代器在追加后仍可安全遍历");
        assertEquals(2, session.events().size(), "后续快照可见新事件");
    }

    @Test
    void deriveMessagesSafeDuringConcurrentAppend() throws Exception {
        // 读侧投影与追加并发隔离（状态面轮询任意时刻调 deriveMessages，agent 线程
        // 同时 append——投影必须遍历快照，撞上活跃列表即抛 ConcurrentModificationException）
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.userMessage("种子"));
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 300 && !done.get(); i++) {
                session.append(SessionEvent.assistantChunk("增量" + i));
            }
            done.set(true);
        });
        writer.start();
        try {
            while (!done.get()) {
                session.deriveMessages();
            }
        } finally {
            done.set(true);
            writer.join();
        }
        assertEquals(301, session.events().size(), "并发期间追加全部落盘");
    }

    @Test
    void approvalEventsRoundTripAndSkipProjection() throws IOException {
        // M6 交互事件：审批请求与决定落会话（审计用），JSONL 往返一致、投影跳过
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.userMessage("帮我写文件"));
        session.append(SessionEvent.approvalRequested("write_file", "{\"path\":\"output.txt\"}"));
        session.append(SessionEvent.approvalDecided("write_file", "allow（回答者: console）"));

        // 单写者语义：会话持有独占锁——同一文件重开需先关闭原属主
        session.close();
        Session reloaded = Session.load(session.jsonl());
        assertEquals(3, reloaded.events().size(), "审批事件随 JSONL 完整往返");
        assertEquals(SessionEvent.APPROVAL_REQUESTED, reloaded.events().get(1).type());
        assertEquals("write_file", reloaded.events().get(1).toolName());
        assertEquals("{\"path\":\"output.txt\"}", reloaded.events().get(1).text());
        assertEquals(SessionEvent.APPROVAL_DECIDED, reloaded.events().get(2).type());
        assertEquals("allow（回答者: console）", reloaded.events().get(2).text());

        List<Message> messages = reloaded.deriveMessages();
        assertEquals(1, messages.size(), "审批事件是审计事件，不进对话投影");
        assertEquals(Message.Role.USER, messages.get(0).role());
    }
}
