package dev.duo.harness.session;

import dev.duo.harness.core.api.PluginException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话事件溯源用例：append 单写（内存 + JSONL 同步落盘）、重放读回一致、
 * 投影规则（message 入列 / chunk 不投影）、空会话、latest 选取。
 */
class SessionTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SessionTest —— 事件溯源：append 落盘与回放、投影规则、尾部窗口映射（边界/回折/孤儿）、可选字段往返（usage/reasoning）、独占锁语义（争用拒绝/释放重开/关闭守卫）、latest 选取与前导非投影事件保留、占用探测与标题投影、子代理事件往返与投影分流、种子边界与中止痕迹、工具结果紧邻修复与崩溃闭合、命令审计两事件（往返/投影排除/配对与窗口零牵动）、压缩点投影（替换/latest-wins/重放恢复/配对零牵动）、权限档投影（latest-wins/重放一致/新会话 null/静态读取不释放持锁）（48 用例） ===");
    }

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
    void toolResultInterleavedByConcurrentAppendMovesAdjacentToItsCall() {
        // BUG-20260917-05：审批阻塞窗口内，子代理完成回流（投影为 user 消息）插在
        // tool/call 与 tool/result 之间 → 修复把 tool 消息前移到配对 assistant
        // 之后紧邻排放（provider 要求 tool 紧随 tool_calls），user 消息后移
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.userMessage("派个活"));
        session.append(SessionEvent.toolCall("call_1", "bash", "{}"));
        session.append(SessionEvent.subagentCompleted("sa-1", "子代理完成：结论 X"));
        session.append(SessionEvent.toolResult("call_1", "bash", "结果文本"));

        List<Message> messages = session.deriveMessages();

        assertEquals(4, messages.size());
        assertEquals(Message.Role.USER, messages.get(0).role());
        assertEquals(Message.Role.ASSISTANT, messages.get(1).role());
        assertEquals(Message.Role.TOOL, messages.get(2).role());
        assertEquals("call_1", messages.get(2).toolCallId());
        assertEquals("结果文本", messages.get(2).content());
        assertEquals(Message.Role.USER, messages.get(3).role());
        assertEquals("子代理完成：结论 X", messages.get(3).content(), "子结论数据源保留");
    }

    @Test
    void loadSealsDanglingToolCallWithSyntheticResult() throws IOException {
        // 崩溃恢复（M16 工单 08）：tool/call 落盘后进程死亡（无 result）→ 打开时
        // 合成闭合——投影合法、可续聊；子会话走同一加载路径自然受益
        Session crashed = Session.create(sessionsDir());
        crashed.append(SessionEvent.userMessage("派个活"));
        crashed.append(SessionEvent.toolCall("call_1", "bash", "{}"));
        crashed.close(); // 模拟进程死亡：result 永远不会落盘

        Session reopened = Session.load(sessionsDir().resolve(
                crashed.id() + ".jsonl"));

        List<Message> messages = reopened.deriveMessages();
        assertEquals(3, messages.size(), "悬空调用已合成闭合（user → tool_calls → tool）");
        assertEquals(Message.Role.TOOL, messages.get(2).role());
        assertEquals("call_1", messages.get(2).toolCallId());
        assertTrue(messages.get(2).content().contains("结果因进程中断未知"),
                "合成闭合文本注明中断未知与只读/幂等重试指引");

        // 会话可续聊：追加新一轮后投影仍合法
        reopened.append(SessionEvent.userMessage("继续"));
        assertEquals(4, reopened.deriveMessages().size());
        assertEquals(Message.Role.USER, reopened.deriveMessages().get(3).role());
    }

    @Test
    void loadLeavesCompleteToolPairsUnchanged() throws IOException {
        // 正常会话（调用与结果配对）：加载零改动——合成闭合只对悬空调用生效
        Session crashed = Session.create(sessionsDir());
        crashed.append(SessionEvent.userMessage("派个活"));
        crashed.append(SessionEvent.toolCall("call_1", "bash", "{}"));
        crashed.append(SessionEvent.toolResult("call_1", "bash", "输出内容"));
        crashed.close();

        int before = Files.readString(sessionsDir().resolve(
                crashed.id() + ".jsonl")).split("\n", -1).length - 1;
        Session reopened = Session.load(sessionsDir().resolve(
                crashed.id() + ".jsonl"));

        assertEquals(before, reopened.events().size(), "配对完整的日志零追加");
        assertEquals(3, reopened.deriveMessages().size(), "投影不受影响");
    }

    @Test
    void orphanToolResultsWithoutCallAreDroppedFromProjection() {
        // 无配对调用的结果无法合法安置（provider 会拒）——投影跳过，日志原样保留
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.userMessage("问"));
        session.append(SessionEvent.toolResult("ghost", "bash", "孤儿结果"));

        List<Message> messages = session.deriveMessages();

        assertEquals(1, messages.size(), "孤儿结果不投影");
        assertEquals(Message.Role.USER, messages.get(0).role());
    }

    @Test
    void emptySessionDerivesEmptyProjection() {
        Session session = Session.create(sessionsDir());

        assertTrue(session.deriveMessages().isEmpty(), "空会话投影应为空列表");
        assertTrue(session.events().isEmpty());
    }

    @Test
    void tailWindowCoversWholeLogWhenUnderLimit() {
        Session session = Session.create(sessionsDir());
        appendRound(session, "第一问", "第一答");
        appendRound(session, "第二问", "第二答");

        Session.TailWindow window = session.tailWindow(50);

        assertEquals(0, window.startEvent(), "不足上限时窗口覆盖全量日志");
        assertEquals(0, window.earlierMessages(), "无更早消息");
    }

    @Test
    void tailWindowStartsAtBoundaryMessageSkippingChunks() {
        // 边界收在消息上：chunk 占事件下标但不投影——窗口起点是尾 50 条消息中首条的事件下标
        Session session = Session.create(sessionsDir());
        for (int i = 0; i < 30; i++) {
            appendRound(session, "问" + i, "答" + i); // 每轮 4 事件（user + 2 chunk + assistant）投影 2 条消息
        }

        Session.TailWindow window = session.tailWindow(50);

        // 60 条消息取尾 50：第 10 条消息（0 基）= 第 6 轮 user，事件下标 5×4 = 20
        assertEquals(20, window.startEvent(), "起点越过 chunk 落在边界消息上");
        assertEquals(10, window.earlierMessages(), "更早计数按投影消息统计");
        assertEquals("问5", session.deriveMessages().get(10).content(), "边界消息确为窗口首条");
    }

    @Test
    void tailWindowFoldsToolResultBackToItsCall() {
        // tool/result 开场即无源之果（前端按 toolCallId 回填调用卡）——回折把同 id 调用一并纳入窗口
        Session session = Session.create(sessionsDir());
        for (int i = 0; i < 10; i++) {
            appendRound(session, "问" + i, "答" + i);                            // 消息 0-19（事件 0-39）
        }
        session.append(SessionEvent.toolCall("call-1", "fs_read", "{}"));        // 消息 20（事件 40）
        session.append(SessionEvent.toolResult("call-1", "fs_read", "文件内容")); // 消息 21（事件 41）
        for (int i = 0; i < 24; i++) {
            appendRound(session, "后问" + i, "后答" + i);                         // 消息 22-69
        }
        session.append(SessionEvent.userMessage("压轴一问"));                     // 消息 70 → 共 71 条

        Session.TailWindow window = session.tailWindow(50);

        // 71 条取尾 50：边界恰落在 tool/result（消息 21）→ 回折到同 id tool/call（事件 40）
        assertEquals(40, window.startEvent(), "窗口起点回折到同 id 的 tool/call");
        assertEquals(20, window.earlierMessages(), "更早计数按回折后的起点统计");
    }

    @Test
    void tailWindowKeepsOrphanToolResultAtOwnBoundary() {
        // 无同 id 调用可回折（异构日志）：结果自身即窗口起点，不回折也不崩溃
        Session session = Session.create(sessionsDir());
        for (int i = 0; i < 36; i++) {
            session.append(SessionEvent.userMessage("问" + i));
            session.append(SessionEvent.assistantMessage("答" + i));
        }
        session.append(SessionEvent.toolResult("ghost", "fs_read", "孤儿结果"));

        Session.TailWindow window = session.tailWindow(1);

        assertEquals(72, window.startEvent(), "无调用可回折：孤儿结果自身即起点");
        assertEquals(72, window.earlierMessages(), "全部 36 轮 72 条消息都在孤儿之前");
    }

    @Test
    void tailWindowKeepsLeadingNonProjectingEventsWhenUntruncated() {
        // 未截断窗口从 0 起：前导非投影事件（悬空审批卡）不得被裁——它们是刷新后重建卡片的数据源
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.approvalRequested("fs_write", "参数摘要"));
        session.append(SessionEvent.userMessage("第一问"));

        Session.TailWindow window = session.tailWindow(50);

        assertEquals(0, window.startEvent(), "未截断不裁前导事件");
        assertEquals(0, window.earlierMessages(), "未截断时窗口之前无消息");
    }

    @Test
    void tailWindowOnEmptySessionYieldsEmptyWindow() {
        Session session = Session.create(sessionsDir());

        Session.TailWindow window = session.tailWindow(50);

        assertEquals(0, window.startEvent(), "空会话窗口区间为空");
        assertEquals(0, window.earlierMessages(), "空会话无更早消息");
    }

    @Test
    void tailWindowRejectsNonPositiveLimit() {
        Session session = Session.create(sessionsDir());
        assertThrows(IllegalArgumentException.class, () -> session.tailWindow(0));
    }

    @Test
    void titleEventRoundTripsWithLatestWins() throws IOException {
        // title 事件（工单 M13-06）：latest-wins 投影、重放保持、不进对话消息投影
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.userMessage("第一问"));
        session.append(SessionEvent.title("初版标题"));
        session.append(SessionEvent.title("定稿标题"));

        assertEquals("定稿标题", session.title(), "latest-wins 取最新");

        session.close();
        Session replayed = Session.load(session.jsonl());
        assertEquals("定稿标题", replayed.title(), "重放读取一致");
        assertTrue(replayed.deriveMessages().stream().noneMatch(m -> "定稿标题".equals(m.content())),
                "标题不进对话消息投影");
        replayed.close();
    }

    @Test
    void isOccupiedReflectsHoldAndReleaseWithoutDisturbingLock() throws IOException {
        // 占用探测（工单 M13-05）：本进程持有 = true、关闭释放后 = false；探测不扰动既有锁
        Session session = Session.create(sessionsDir());

        assertTrue(Session.isOccupied(session.jsonl()), "本进程持有 → 占用");
        session.close();
        assertTrue(!Session.isOccupied(session.jsonl()), "关闭释放 → 未占用");
        assertTrue(!Session.isOccupied(sessionsDir().resolve("不存在.jsonl")), "文件不存在按未占用");

        Session reopened = Session.load(session.jsonl());
        assertEquals(session.id(), reopened.id(), "探测未扰动锁：同文件可重新持锁");
        reopened.close();
    }

    @Test
    void windowBeforeAtLogEndEqualsTailWindow() {
        // 分页窗口是 tailWindow 的右边界参数化形态：右边界取日志末尾时两者全等
        Session session = Session.create(sessionsDir());
        for (int i = 0; i < 10; i++) {
            appendRound(session, "问" + i, "答" + i);
        }
        session.append(SessionEvent.toolCall("call-1", "fs_read", "{}"));
        session.append(SessionEvent.toolResult("call-1", "fs_read", "文件内容"));

        Session.TailWindow atEnd = session.windowBefore(session.events().size(), 50);
        Session.TailWindow tail = session.tailWindow(50);

        assertEquals(tail.startEvent(), atEnd.startEvent(), "右边界 = 事件数时与 tailWindow 等价");
        assertEquals(tail.earlierMessages(), atEnd.earlierMessages(), "更早计数一致");
    }

    @Test
    void windowBeforeTruncatedTakesTrailingMessagesOfRange() {
        // 截断分页：右边界之前的区间取尾 50 条消息，起点收在该区间首条消息的事件下标
        Session session = Session.create(sessionsDir());
        for (int i = 0; i < 30; i++) {
            session.append(SessionEvent.userMessage("问" + i));
            session.append(SessionEvent.assistantMessage("答" + i));
        }

        Session.TailWindow truncated = session.windowBefore(60, 50);

        assertEquals(10, truncated.startEvent(), "区间 [0,60) 共 60 条取尾 50：起点 = 第 10 条消息（问5）");
        assertEquals(10, truncated.earlierMessages(), "更早计数按区间内投影消息统计");

        Session.TailWindow full = session.windowBefore(50, 50);

        assertEquals(0, full.startEvent(), "区间内消息恰不超上限 → 全量");
        assertEquals(0, full.earlierMessages());
    }

    @Test
    void windowBeforeRejectsOutOfRangeEnd() {
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.userMessage("问"));

        assertThrows(IllegalArgumentException.class, () -> session.windowBefore(-1, 50));
        assertThrows(IllegalArgumentException.class, () -> session.windowBefore(2, 50));
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
    void eventsReturnsSharedImmutableSnapshot() {
        // CoW 快照契约（ADR-0014）：无追加期间 events() 返回同一共享不可变实例
        // （读侧零拷贝的可观察形态），append 后快照重建、新事件经新快照可见
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.userMessage("一"));
        List<SessionEvent> first = session.events();

        assertSame(first, session.events(), "无追加期间共享同一快照引用");
        assertThrows(UnsupportedOperationException.class,
                () -> first.add(SessionEvent.userMessage("写快照")), "快照不可变");

        session.append(SessionEvent.userMessage("二"));
        assertNotSame(first, session.events(), "追加后快照重建");
        assertEquals(2, session.events().size(), "新快照可见追加事件");
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

    @Test
    void subagentEventsRoundTripWithProjectionRules() throws IOException {
        // M15 子代理引用事件：spawned/completed 落父会话，JSONL 往返一致；
        // 投影分流——completed 最终回答进父上下文（父聚合结果的数据源），spawned 卡片专用跳过
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.userMessage("帮我调研"));
        session.append(SessionEvent.subagentSpawned("sa-1", "researcher",
                "{\"task\":\"调研 X\",\"mode\":\"spawn\"}"));
        session.append(SessionEvent.subagentCompleted("sa-1", "子代理 sa-1 已完成。\n最终回答：结论 ……"));

        session.close();
        Session reloaded = Session.load(session.jsonl());
        assertEquals(3, reloaded.events().size(), "子代理事件随 JSONL 完整往返");
        SessionEvent spawned = reloaded.events().get(1);
        assertEquals(SessionEvent.SUBAGENT_SPAWNED, spawned.type());
        assertEquals("sa-1", spawned.toolCallId(), "子 agent id 走关联 id 可选位");
        assertEquals("researcher", spawned.toolName(), "模板名走工具名可选位");
        assertEquals("{\"task\":\"调研 X\",\"mode\":\"spawn\"}", spawned.text(), "载荷 JSON 透明往返");
        SessionEvent completed = reloaded.events().get(2);
        assertEquals(SessionEvent.SUBAGENT_COMPLETED, completed.type());
        assertEquals("sa-1", completed.toolCallId(), "completed 以同 id 关联 spawned");
        assertEquals("子代理 sa-1 已完成。\n最终回答：结论 ……", completed.text());

        List<Message> messages = reloaded.deriveMessages();
        assertEquals(2, messages.size(), "spawned 跳过投影，completed 投影进父上下文");
        assertEquals(Message.Role.USER, messages.get(1).role(),
                "子代理结果以 USER 形态进入父 LLM 上下文（tool 关联位已被 spawn 调用消费）");
        assertEquals("子代理 sa-1 已完成。\n最终回答：结论 ……", messages.get(1).content());
    }

    @Test
    void commandEventsRoundTripAndSkipProjection() throws IOException {
        // M19 命令审计两事件（ADR-0020 决策 5）：run/done 落会话（崩溃断口可观测）、
        // JSONL 往返一致、投影排除——命令操作 harness 不进模型历史
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.userMessage("看看权限"));
        session.append(SessionEvent.commandRun("permission", ""));
        session.append(SessionEvent.commandDone("permission", "当前预设: read-only（可选: …）"));

        session.close();
        Session reloaded = Session.load(session.jsonl());
        assertEquals(3, reloaded.events().size(), "命令事件随 JSONL 完整往返");
        SessionEvent run = reloaded.events().get(1);
        assertEquals(SessionEvent.COMMAND_RUN, run.type());
        assertEquals("permission", run.toolName(), "命令名走工具名可选位");
        assertEquals("", run.text(), "参数文本走载荷位");
        SessionEvent done = reloaded.events().get(2);
        assertEquals(SessionEvent.COMMAND_DONE, done.type());
        assertEquals("permission", done.toolName());
        assertEquals("当前预设: read-only（可选: …）", done.text());

        List<Message> messages = reloaded.deriveMessages();
        assertEquals(1, messages.size(), "命令事件不进对话投影（模型不可见由投影纯函数保证）");
        assertEquals(Message.Role.USER, messages.get(0).role());
    }

    @Test
    void commandEventsDoNotDisturbToolPairingOrWindowCount() {
        // M19 零牵动断言（ADR-0020 决策 5）：命令事件插在 tool/call 与 tool/result
        // 之间——紧邻修复照常成立（只认 tool/call|result）；尾窗计数不含命令事件
        // （只数投影消息），窗口边界不受影响
        Session session = Session.create(sessionsDir());
        appendRound(session, "第一问", "第一答");
        session.append(SessionEvent.userMessage("派个活"));
        session.append(SessionEvent.toolCall("call_1", "bash", "{}"));
        session.append(SessionEvent.commandRun("permission", "read-only"));
        session.append(SessionEvent.commandDone("permission", "已切换: read-only"));
        session.append(SessionEvent.toolResult("call_1", "bash", "结果文本"));

        List<Message> messages = session.deriveMessages();
        // user, assistant, user, assistant(tool_calls), tool —— 命令两事件不占位，
        // tool 消息仍前移到配对 assistant 之后紧邻（修复对命令事件透明）
        assertEquals(5, messages.size());
        assertEquals(Message.Role.TOOL, messages.get(4).role());
        assertEquals("call_1", messages.get(4).toolCallId());
        assertEquals(Message.Role.ASSISTANT, messages.get(3).role());
        assertNotNull(messages.get(3).toolCalls());

        // 尾窗 max=2：最后两条投影消息是 assistant(tool_calls) 与 tool——命令事件
        // 不算消息，窗口起点收在 assistant(tool_calls)（回折不需要，调用本就在窗内）
        Session.TailWindow window = session.tailWindow(2);
        List<SessionEvent> events = session.events();
        assertEquals(events.size() - 4, window.startEvent(),
                "窗口起点 = assistant(tool_calls) 事件下标（命令事件不占消息计数）");
        assertEquals(3, window.earlierMessages(), "起点之前 3 条投影消息（命令不计）");
    }

    @Test
    void compactionEventReplacesPriorHistoryWithSummary() throws IOException {
        // 压缩点投影（M19，ADR-0020 决策 6）：compacted 之前的一切以总结替换、之后照常；
        // 重开会话经日志重放天然恢复压缩态（不重复总结的数据源）
        Session session = Session.create(sessionsDir());
        appendRound(session, "第一问", "第一答");
        appendRound(session, "第二问", "第二答");
        session.append(SessionEvent.compaction("## 主要请求\n压缩总结全文", "manual"));
        session.append(SessionEvent.userMessage("压缩后的问题"));

        List<Message> messages = session.deriveMessages();
        assertEquals(2, messages.size(), "替换头 + 压缩后消息");
        assertEquals(Message.Role.USER, messages.get(0).role());
        assertTrue(messages.get(0).content().contains("压缩总结全文"), "总结文本入投影");
        assertTrue(messages.get(0).content().startsWith("[以下是本会话早期历史的压缩摘要"),
                "替换头与治理管线折叠骨架同文");
        assertEquals("压缩后的问题", messages.get(1).content(), "压缩点之后照常");

        // 重放：压缩态经 JSONL 恢复一致
        session.close();
        Session reloaded = Session.load(session.jsonl());
        List<Message> replayed = reloaded.deriveMessages();
        assertEquals(2, replayed.size());
        assertEquals(messages.get(0).content(), replayed.get(0).content(), "重放投影一致");
        reloaded.close();
    }

    @Test
    void multipleCompactionEventsLatestWins() {
        // latest-wins：后一压缩点的总结涵盖更早历史（含前一压缩点）——投影取最后压缩点
        Session session = Session.create(sessionsDir());
        appendRound(session, "第一问", "第一答");
        session.append(SessionEvent.compaction("第一次总结", "auto"));
        session.append(SessionEvent.userMessage("中间一问"));
        session.append(SessionEvent.compaction("第二次总结（涵盖一切）", "manual"));
        session.append(SessionEvent.userMessage("最后问题"));

        List<Message> messages = session.deriveMessages();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0).content().contains("第二次总结（涵盖一切）"),
                "最后压缩点胜出");
        assertEquals("最后问题", messages.get(1).content());
        session.close();
    }

    @Test
    void compactionPointDoesNotBreakToolPairingAndCountsAsWindowMessage() {
        // 零牵动断言：压缩点后新开的工具对照常配对（压缩点只切历史，不影响之后）；
        // 替换头是投影消息——占窗口计数（它是上下文的一部分）
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.compaction("历史总结", "manual"));
        session.append(SessionEvent.userMessage("压缩后的任务"));
        session.append(SessionEvent.toolCall("call_1", "bash", "{}"));
        session.append(SessionEvent.toolResult("call_1", "bash", "输出"));

        List<Message> messages = session.deriveMessages();
        // 替换头, user, assistant(tool_calls), tool——配对零牵动
        assertEquals(4, messages.size());
        assertEquals(Message.Role.TOOL, messages.get(3).role());
        assertEquals("call_1", messages.get(3).toolCallId());

        Session.TailWindow window = session.tailWindow(3);
        // 4 条投影消息（替换头, user, assistant(tool_calls), tool）取尾 3 条：
        // 起点收在 user（下标 1），替换头是起点之前的 1 条更早消息（计入投影、占分页计数）
        assertEquals(1, window.startEvent(), "替换头计入投影消息（窗口不含它时为 earlier）");
        assertEquals(1, window.earlierMessages());
        session.close();
    }

    @Test
    void permissionModeRoundTripsWithLatestWins() throws IOException {
        // 权限档投影（M19，ADR-0020 决策 10）：latest-wins（plan/mode 同款先例）、
        // JSONL 重放一致；无切档事件的新会话返回 null（调用方回退 yml 缺省）
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.permissionMode("read-only"));
        session.append(SessionEvent.permissionMode("danger-full-access"));
        assertEquals("danger-full-access", session.permissionMode(), "latest-wins 取最后档");

        session.close();
        Session reloaded = Session.load(session.jsonl());
        assertEquals("danger-full-access", reloaded.permissionMode(), "重放投影一致");
        reloaded.close();

        Session fresh = Session.create(sessionsDir());
        assertNull(fresh.permissionMode(), "新会话无切档记录");
        fresh.close();
    }

    @Test
    void permissionRulesProjectionLatestWinsAndSurvivesUserMessage() throws IOException {
        // 会话级权限规则投影（M24 工单 01，ADR-0026 决策一）：latest-wins 快照、
        // JSONL 重放一致；与 todo 投影不同——user/message 不清空（规则随会话生命周期，
        // 不随新轮失效）；新会话无规则事件返回 null（按空规则处理）
        Session session = Session.create(sessionsDir());
        assertNull(session.permissionRules(), "新会话无规则事件");
        session.append(SessionEvent.permissionRules(
                "[{\"tool\":\"bash\",\"prefix\":\"docker logs\",\"decision\":\"allow\"}]"));
        session.append(SessionEvent.userMessage("新的一轮"));
        assertEquals("[{\"tool\":\"bash\",\"prefix\":\"docker logs\",\"decision\":\"allow\"}]",
                session.permissionRules(), "user/message 不清空规则（区别于 todo 投影）");
        session.append(SessionEvent.permissionRules("[]"));
        assertEquals("[]", session.permissionRules(), "latest-wins 取最新快照");

        session.close();
        Session reloaded = Session.load(session.jsonl());
        assertEquals("[]", reloaded.permissionRules(), "重放投影一致");
        reloaded.close();
    }

    @Test
    void modelIntentProjectionLatestWins() throws IOException {
        // 模型意图投影（M24 工单 09，ADR-0026 决策六）：latest-wins（permissionMode
        // 同款先例）；无切换事件的新会话返回 null
        Session session = Session.create(sessionsDir());
        assertNull(session.modelIntent(), "新会话无切换事件");
        session.append(SessionEvent.modelIntent("deepseek-chat"));
        session.append(SessionEvent.modelIntent("claude-sonnet-4-5"));
        assertEquals("claude-sonnet-4-5", session.modelIntent(), "latest-wins 取最后意图");

        session.close();
        Session reloaded = Session.load(session.jsonl());
        assertEquals("claude-sonnet-4-5", reloaded.modelIntent(), "重放投影一致");
        reloaded.close();
    }

    @Test
    void permissionModeOfDoesNotReleaseHeldLock() throws IOException {
        // OCR #15 回归：本进程持锁期间经 permissionModeOf 读同文件——只读 fd 有意不关
        // （POSIX 陷阱：关闭任意 fd 释放进程全部锁）。探测方式：新通道 tryLock 必须
        // 因重叠锁失败；若失败前被读取路径释放，tryLock 会意外成功
        Session holder = Session.create(sessionsDir());
        holder.append(SessionEvent.permissionMode("read-only"));

        assertEquals("read-only", Session.permissionModeOf(holder.jsonl()), "读取本身正常");

        // 同进程新通道探测锁仍在：tryLock 抛 OverlappingFileLockException = 锁未被释放
        try (var probe = java.nio.channels.FileChannel.open(holder.jsonl(),
                java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE)) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.nio.channels.OverlappingFileLockException.class,
                    probe::tryLock,
                    "读取路径不得释放属主的独占锁（POSIX 陷阱）");
        }

        // 无人持锁的文件照常读取且正常关闭
        Session other = Session.create(sessionsDir());
        other.append(SessionEvent.permissionMode("danger-full-access"));
        other.close();
        assertEquals("danger-full-access", Session.permissionModeOf(other.jsonl()));
        holder.close();
    }

    @Test
    void legacyLinesAndSubagentEventsCoexist() throws IOException {
        // 旧格式行（仅 type/at/text 三字段）与新事件混排：load 不崩，投影各归各位——
        // 既有会话文件在升级后读取行为不变
        Path jsonl = sessionsDir().resolve("legacy-mix.jsonl");
        Files.createDirectories(sessionsDir());
        Files.write(jsonl, List.of(
                "{\"type\":\"user/message\",\"at\":1000,\"text\":\"旧格式一问\"}",
                "{\"type\":\"assistant/message\",\"at\":2000,\"text\":\"旧格式一答\"}",
                "{\"type\":\"subagent/spawned\",\"at\":3000,"
                        + "\"text\":\"{\\\"task\\\":\\\"T\\\",\\\"mode\\\":\\\"fork\\\"}\","
                        + "\"toolCallId\":\"sa-9\",\"toolName\":\"researcher\"}",
                "{\"type\":\"subagent/completed\",\"at\":4000,\"text\":\"fork 子代理结论\",\"toolCallId\":\"sa-9\"}"));

        Session reloaded = Session.load(jsonl);
        assertEquals(4, reloaded.events().size(), "旧格式行与新事件行全部读回");
        assertEquals("researcher", reloaded.events().get(2).toolName());

        List<Message> messages = reloaded.deriveMessages();
        assertEquals(3, messages.size(), "旧格式消息照常投影，spawned 跳过，completed 入列");
        assertEquals("旧格式一问", messages.get(0).content());
        assertEquals("旧格式一答", messages.get(1).content());
        assertEquals("fork 子代理结论", messages.get(2).content());
        reloaded.close();
    }

    @Test
    void seedBoundaryEventRoundTripsAndSkipsProjection() throws IOException {
        // M15 fork 播种的种子边界（工单 03）：子会话审计标记——往返一致、不进对话投影
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.subagentSeedBoundary("parent-1", 7));

        session.close();
        Session reloaded = Session.load(session.jsonl());
        assertEquals(1, reloaded.events().size(), "种子边界随 JSONL 往返");
        assertEquals(SessionEvent.SUBAGENT_SEED_BOUNDARY, reloaded.events().get(0).type());
        assertEquals("前 7 条来自父会话 parent-1", reloaded.events().get(0).text());
        assertTrue(reloaded.deriveMessages().isEmpty(), "边界是审计标记，不进对话投影");
        reloaded.close();
    }

    @Test
    void interruptedEventRoundTripsAndSkipsProjection() throws IOException {
        // M15 控制面中止痕迹（工单 04）：子会话侧的终止审计——往返一致、不进对话投影
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.subagentInterrupted("sa-5", "被父 agent 中止"));

        session.close();
        Session reloaded = Session.load(session.jsonl());
        assertEquals(SessionEvent.SUBAGENT_INTERRUPTED, reloaded.events().get(0).type());
        assertEquals("sa-5", reloaded.events().get(0).toolCallId());
        assertEquals("被父 agent 中止", reloaded.events().get(0).text());
        assertTrue(reloaded.deriveMessages().isEmpty(), "中止痕迹是审计事件，不进对话投影");
        reloaded.close();
    }
}
