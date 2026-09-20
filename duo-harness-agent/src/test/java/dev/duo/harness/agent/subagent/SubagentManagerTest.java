package dev.duo.harness.agent.subagent;

import dev.duo.harness.agent.subagent.backend.SubagentBackend;
import dev.duo.harness.agent.subagent.tools.ListAgentsTool;
import dev.duo.harness.agent.subagent.tools.SendMessageTool;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子代理管理者用例（工单 03/04，ADR-0015 决策 2/3/4）：spawn 立即返回 + 后台
 * 完成回流（latch 确定性时序）、运行期子会话持锁 / 完成后释放可回放、子目录
 * 存放与侧栏天然排除、fork 播种（平衡前缀 + 种子边界）；控制面三件——
 * send_message 运行中纠偏与空闲续轮、interrupt_agent 中止路径（子会话终止痕迹
 * + 父回流"已被中止"）、list_agents 状态呈现；未知模板与不可达状态点名。
 * 后端以可控 mock 注入（执行链语义归端到端用例）。
 */
class SubagentManagerTest {

    @TempDir
    Path tempDir;

    private Session parent;
    private SubagentManager manager;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SubagentManagerTest —— 生命周期：spawn 立即返回/latch 确定性回流、"
                + "运行期持锁与完成释放、子目录与侧栏排除、fork 播种与种子边界；控制面：运行中纠偏/空闲续轮/"
                + "interrupt 中止痕迹/list 状态、未完成回流带成果、会话归属过滤、不可达状态点名（14 用例） ===");
    }

    @BeforeEach
    void setUp() {
        parent = Session.create(sessionsDir());
        manager = newManager();
    }

    @AfterEach
    void tearDown() {
        parent.close();
    }

    private Path sessionsDir() {
        return tempDir.resolve("sessions");
    }

    private SubagentManager newManager() {
        return new SubagentManager(SubagentTemplates.parse(config(
                "{\"templates\": [{\"name\": \"worker\", \"tools\": [\"fs_read\"]}]}")));
    }

    private static com.fasterxml.jackson.databind.JsonNode config(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 即时完成的后端：返回固定结论。 */
    private static SubagentBackend instantBackend(String answer) {
        return task -> new SubagentBackend.Outcome(answer, true, null);
    }

    /** 在指定时刻放行的可控后端：started 通知已开跑，release 放行完成。 */
    private static SubagentBackend gatedBackend(CountDownLatch started, CountDownLatch release,
                                                String answer) {
        return task -> {
            started.countDown();
            release.await();
            return new SubagentBackend.Outcome(answer, true, null);
        };
    }

    /** 轮询等待父会话出现"未见过的" completed 事件（回流在后台线程，至多 5 秒）。 */
    private static SessionEvent awaitCompleted(Session session, Set<SessionEvent> seen)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            for (SessionEvent event : session.events()) {
                if (SessionEvent.SUBAGENT_COMPLETED.equals(event.type()) && !seen.contains(event)) {
                    seen.add(event);
                    return event;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待 completed 回流超时");
    }

    /** 轮询等待子会话锁释放（close 在回流之后，至多 5 秒）。 */
    private static void awaitReleased(Path childJsonl) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (!Session.isOccupied(childJsonl)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待子会话锁释放超时: " + childJsonl);
    }

    // ===== 生命周期（工单 03） =====

    @Test
    void spawnReturnsImmediatelyAndReflowsOnCompletion() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        manager.bindBackend(gatedBackend(started, release, "子代理结论"));

        String agentId = manager.spawn(parent, "worker", "调研任务");
        assertTrue(started.await(5, TimeUnit.SECONDS), "后台任务已开跑");
        assertEquals(1, parent.events().size(), "spawn 立即返回：父会话此刻只有 spawned 事件");
        assertEquals(SubagentManager.State.RUNNING, manager.byId(agentId).orElseThrow().state());

        SessionEvent spawned = parent.events().get(0);
        assertEquals(SessionEvent.SUBAGENT_SPAWNED, spawned.type());
        assertEquals(agentId, spawned.toolCallId(), "子 agent id 走关联 id 位");
        assertEquals("worker", spawned.toolName(), "模板名走工具名位");
        assertTrue(spawned.text().contains("\"mode\":\"spawn\""), "载荷含模式");

        release.countDown();
        SessionEvent completed = awaitCompleted(parent, new HashSet<>());
        assertEquals(agentId, completed.toolCallId(), "completed 以同 id 关联");
        assertTrue(completed.text().contains("子代理结论"), "最终回答随回流进父会话");
        assertEquals(SubagentManager.State.IDLE, manager.byId(agentId).orElseThrow().state(),
                "正常完成 → 空闲（可续轮）");
    }

    @Test
    void childSessionLockedWhileRunningAndReplayableAfterCompletion() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        manager.bindBackend(task -> {
            started.countDown();
            release.await();
            task.session().append(SessionEvent.assistantMessage("子全过程"));
            return new SubagentBackend.Outcome("done", true, null);
        });

        String agentId = manager.spawn(parent, "worker", "任务");
        Path childJsonl = sessionsDir().resolve(SubagentManager.SUBDIRECTORY).resolve(agentId + ".jsonl");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(Session.isOccupied(childJsonl), "运行期子会话持锁（外部打开即撞锁）");

        release.countDown();
        awaitCompleted(parent, new HashSet<>());
        awaitReleased(childJsonl);
        assertFalse(Session.isOccupied(childJsonl), "完成后释放锁");

        Session replayed = Session.load(childJsonl);
        assertTrue(replayed.events().stream()
                .anyMatch(e -> "子全过程".equals(e.text())), "完成后可打开回放子代理全程");
        replayed.close();
    }

    @Test
    void childSessionsLiveInSubdirectoryAndStayOutOfSidebar() {
        manager.bindBackend(instantBackend("done"));
        manager.spawn(parent, "worker", "任务一");
        manager.spawn(parent, "worker", "任务二");

        assertTrue(java.nio.file.Files.isDirectory(sessionsDir().resolve(SubagentManager.SUBDIRECTORY)),
                "子会话存放于会话目录的子目录");
        List<Session.SessionSummary> sidebar = Session.list(sessionsDir());
        assertEquals(1, sidebar.size(), "侧栏只见父会话——子会话不进侧栏列表");
        assertEquals(parent.id(), sidebar.get(0).id());
    }

    @Test
    void forkSeedsBalancedPrefixWithBoundary() throws Exception {
        parent.append(SessionEvent.userMessage("背景一问"));
        parent.append(SessionEvent.assistantMessage("背景一答"));
        parent.append(SessionEvent.userMessage("半截追问")); // 悬空轮：不进播种

        manager.bindBackend(instantBackend("done"));
        String agentId = manager.fork(parent, "worker", "深挖任务");

        SessionEvent spawned = parent.events().get(3); // [user, assistant, 悬空 user, spawned]
        assertTrue(spawned.text().contains("\"mode\":\"fork\""), "fork 模式入载荷");
        assertTrue(spawned.text().contains(parent.id()), "fork 源引用（父会话 id）入载荷");

        Path childJsonl = sessionsDir().resolve(SubagentManager.SUBDIRECTORY).resolve(agentId + ".jsonl");
        awaitCompleted(parent, new HashSet<>());
        awaitReleased(childJsonl);

        Session child = Session.load(childJsonl);
        List<SessionEvent> events = child.events();
        assertEquals(3, events.size(), "播种 2 条 + 种子边界（本用例后端不追加子事件）");
        assertEquals(SessionEvent.USER_MESSAGE, events.get(0).type(), "父平衡前缀原样落子日志开头");
        assertEquals("背景一问", events.get(0).text());
        assertEquals("背景一答", events.get(1).text());
        assertEquals(SessionEvent.SUBAGENT_SEED_BOUNDARY, events.get(2).type());
        assertEquals("前 2 条来自父会话 " + parent.id(), events.get(2).text(), "种子边界标记");
        assertTrue(child.deriveMessages().size() >= 2, "播种段可投影（子会话自包含可冷读）");
        child.close();
    }

    @Test
    void forkSeedFiltersUserAttachmentBlocks() throws Exception {
        // M21（ADR-0022 决策 10）：附件引用块不进子代理上下文——种子处物理拦截
        parent.append(SessionEvent.userMessage("背景一问"));
        parent.append(SessionEvent.userAttachment(
                new dev.duo.harness.session.AttachmentRef("a".repeat(64), "image/png", 1024, "图.png").toJson()));
        parent.append(SessionEvent.assistantMessage("背景一答"));

        manager.bindBackend(instantBackend("done"));
        String agentId = manager.fork(parent, "worker", "深挖任务");
        Path childJsonl = sessionsDir().resolve(SubagentManager.SUBDIRECTORY).resolve(agentId + ".jsonl");
        awaitCompleted(parent, new HashSet<>());
        awaitReleased(childJsonl);

        Session child = Session.load(childJsonl);
        List<SessionEvent> events = child.events();
        assertTrue(events.stream().noneMatch(e -> SessionEvent.USER_ATTACHMENT.equals(e.type())),
                "子日志不得含附件引用块: " + events);
        assertTrue(events.stream().anyMatch(e -> "背景一问".equals(e.text())), "其余播种内容保留");
        assertTrue(events.stream().anyMatch(e -> "背景一答".equals(e.text())), "其余播种内容保留");
        child.close();
    }

    @Test
    void backendFailureReflowsAsIncomplete() throws Exception {
        manager.bindBackend(task -> {
            throw new IllegalStateException("LLM 炸了");
        });
        String agentId = manager.spawn(parent, "worker", "会炸的任务");

        SessionEvent completed = awaitCompleted(parent, new HashSet<>());
        assertTrue(completed.text().contains("未正常完成"), "失败以标注回流，父 LLM 可感知");
        assertTrue(completed.text().contains("LLM 炸了"));
        assertEquals(SubagentManager.State.FAILED, manager.byId(agentId).orElseThrow().state());
    }

    @Test
    void incompleteReflowCarriesPartialResultsAndResumeHint() throws Exception {
        // 迭代上限触达（真实场景：子代理 10 轮 27 次工具调用后仍未收尾）——
        // 父 agent 必须拿到已完成的中间成果与续轮指引，而不是一句"未完成"
        manager.bindBackend(task -> new SubagentBackend.Outcome(
                "已执行 27 次工具调用：\n1. glob **/pom.xml → 637 字符\n末次结果摘录：\n模块清单…",
                false, "已达迭代上限（30 轮），未产出最终回答"));

        String agentId = manager.spawn(parent, "worker", "大调研任务");
        SessionEvent completed = awaitCompleted(parent, new HashSet<>());

        assertTrue(completed.text().contains("未正常完成"), completed.text());
        assertTrue(completed.text().contains("已达迭代上限"), "失败原因可见");
        assertTrue(completed.text().contains("已完成的中间成果"), "部分成果随回流带给父 agent");
        assertTrue(completed.text().contains("27 次工具调用"), "工具调用摘要可见");
        assertTrue(completed.text().contains("send_message"), "续轮指引可见（FAILED 态可续）");
        assertEquals(SubagentManager.State.FAILED, manager.byId(agentId).orElseThrow().state());

        // 指引不是空头承诺：FAILED 态确可经 send_message 续轮
        assertTrue(manager.sendMessage(agentId, "继续收尾").contains("新一轮"), "FAILED 可续轮");
    }

    @Test
    void unknownTemplateFailsLoudly() {
        manager.bindBackend(instantBackend("done"));
        assertThrows(PluginException.class,
                () -> manager.spawn(parent, "ghost", "任务"));
        assertTrue(manager.all().isEmpty(), "失败启动不残留注册表条目");
    }

    // ===== 控制面三件（工单 04） =====

    @Test
    void sendMessageToRunningChildSteersNextRound() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        manager.bindBackend(task -> {
            started.countDown();
            // 等待父补充指示出现在子会话投影——steer 经事件溯源在下一轮生效
            long deadline = System.currentTimeMillis() + 5_000;
            boolean steered = false;
            while (System.currentTimeMillis() < deadline) {
                steered = task.session().deriveMessages().stream()
                        .anyMatch(m -> m.content().contains("改用方案 B"));
                if (steered) {
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(steered, "运行中的子代理在其上下文里看到了父指示");
            return new SubagentBackend.Outcome("按方案 B 完成", true, null);
        });

        String agentId = manager.spawn(parent, "worker", "调研任务");
        assertTrue(started.await(5, TimeUnit.SECONDS));

        String ack = manager.sendMessage(agentId, "改用方案 B");
        assertTrue(ack.contains("下一轮生效"), "运行中 → 纠偏确认");

        Set<SessionEvent> seen = new HashSet<>();
        SessionEvent completed = awaitCompleted(parent, seen);
        assertTrue(completed.text().contains("按方案 B 完成"), "子代理按纠偏后的方向完成");

        Path childJsonl = sessionsDir().resolve(SubagentManager.SUBDIRECTORY).resolve(agentId + ".jsonl");
        awaitReleased(childJsonl);
        Session child = Session.load(childJsonl);
        assertTrue(child.events().stream()
                        .anyMatch(e -> "[父补充指示] 改用方案 B".equals(e.text())),
                "指示留痕子会话（可审计）");
        child.close();
    }

    @Test
    void sendMessageToIdleChildStartsNewRound() throws Exception {
        AtomicInteger rounds = new AtomicInteger();
        CountDownLatch round2Started = new CountDownLatch(1);
        CountDownLatch round2Release = new CountDownLatch(1);
        manager.bindBackend(task -> {
            if (rounds.incrementAndGet() == 1) {
                task.session().append(SessionEvent.userMessage(task.description()));
                return new SubagentBackend.Outcome("第一轮结论", true, null);
            }
            round2Started.countDown();
            round2Release.await(); // 第二轮可控放行，锁定 RUNNING 窗口的断言
            task.session().append(SessionEvent.userMessage(task.description()));
            return new SubagentBackend.Outcome("第二轮结论", true, null);
        });

        String agentId = manager.spawn(parent, "worker", "第一轮任务");
        Set<SessionEvent> seen = new HashSet<>();
        awaitCompleted(parent, seen);
        assertEquals(SubagentManager.State.IDLE, manager.byId(agentId).orElseThrow().state());

        String ack = manager.sendMessage(agentId, "接着做第二轮");
        assertTrue(ack.contains("新一轮"), "空闲 → 开新轮确认");
        assertTrue(round2Started.await(5, TimeUnit.SECONDS));
        assertEquals(SubagentManager.State.RUNNING, manager.byId(agentId).orElseThrow().state(),
                "续轮期间回到运行中");

        round2Release.countDown();
        awaitCompleted(parent, seen);
        assertEquals(2, rounds.get(), "同一后端跑了两个轮次");
        assertEquals(SubagentManager.State.IDLE, manager.byId(agentId).orElseThrow().state());

        Path childJsonl = sessionsDir().resolve(SubagentManager.SUBDIRECTORY).resolve(agentId + ".jsonl");
        awaitReleased(childJsonl);
        Session child = Session.load(childJsonl);
        assertTrue(child.deriveMessages().stream().anyMatch(m -> m.content().contains("第一轮任务")),
                "同一子会话承载全部轮次（历史延续）");
        assertTrue(child.deriveMessages().stream().anyMatch(m -> m.content().contains("接着做第二轮")),
                "续轮任务入同一子会话");
        child.close();
    }

    @Test
    void interruptAbortsRunningChildWithTrace() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        manager.bindBackend(gatedBackend(started, release, "不该出现的结论"));

        String agentId = manager.spawn(parent, "worker", "跑偏的任务");
        assertTrue(started.await(5, TimeUnit.SECONDS));

        String ack = manager.interrupt(agentId);
        assertTrue(ack.contains("中止"), "中止确认");
        release.countDown();

        Set<SessionEvent> seen = new HashSet<>();
        SessionEvent completed = awaitCompleted(parent, seen);
        assertTrue(completed.text().contains("已被中止"), "父会话收到中止终局回流");
        assertEquals(SubagentManager.State.INTERRUPTED, manager.byId(agentId).orElseThrow().state());

        Path childJsonl = sessionsDir().resolve(SubagentManager.SUBDIRECTORY).resolve(agentId + ".jsonl");
        awaitReleased(childJsonl);
        Session child = Session.load(childJsonl);
        assertTrue(child.events().stream()
                        .anyMatch(e -> SessionEvent.SUBAGENT_INTERRUPTED.equals(e.type())),
                "子会话留下可审计的中止痕迹");
        child.close();

        assertThrows(PluginException.class, () -> manager.sendMessage(agentId, "再试"),
                "中断是终态——拒绝续轮");
        assertThrows(PluginException.class, () -> manager.interrupt(agentId),
                "已中止的不可再中止");
    }

    @Test
    void listAgentsRendersStates() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        manager.bindBackend(gatedBackend(started, release, "结论"));
        ListAgentsTool listTool = new ListAgentsTool(manager, () -> parent);

        assertEquals("当前没有子代理。", listTool.execute(null), "空注册表呈现");

        String runningId = manager.spawn(parent, "worker", "还在跑");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        String running = (String) listTool.execute(null);
        assertTrue(running.contains(runningId) && running.contains("运行中"), "运行中状态呈现");

        release.countDown();
        awaitCompleted(parent, new HashSet<>());
        String idle = (String) listTool.execute(null);
        assertTrue(idle.contains("空闲"), "完成 → 空闲呈现");
        assertTrue(idle.contains("worker"), "模板名呈现");
    }

    @Test
    void controlPlaneIsScopedToTheCallingParentSession() throws Exception {
        // 会话归属治理：长驻呈现位换绑后，其他会话派的子代理不可见、不可操作——
        // 否则旧会话的子代理会被新对话的模型误治理
        manager.bindBackend(instantBackend("done"));
        String agentId = manager.spawn(parent, "worker", "A 会话的子代理");
        awaitCompleted(parent, new HashSet<>());

        Session other = Session.create(sessionsDir());
        try {
            ListAgentsTool otherList = new ListAgentsTool(manager, () -> other);
            assertEquals("当前没有子代理。", (String) otherList.execute(null), "其他会话视角零可见");

            var args = config("{\"agentId\": \"" + agentId + "\", \"message\": \"hello\"}");
            var ex = assertThrows(PluginException.class,
                    () -> new SendMessageTool(manager, () -> other).execute(
                            new dev.duo.harness.tools.ToolExecution("send_message", args)));
            assertTrue(ex.getMessage().contains("属于会话") && ex.getMessage().contains(parent.id()),
                    "归属拒绝点名双方会话: " + ex.getMessage());

            ListAgentsTool ownList = new ListAgentsTool(manager, () -> parent);
            assertTrue(((String) ownList.execute(null)).contains(agentId), "本会话视角可见可操作");
        } finally {
            other.close();
        }
    }

    @Test
    void controlPlaneRejectsUnknownAgentAndNonSteerableStates() throws Exception {
        manager.bindBackend(instantBackend("done"));
        assertThrows(PluginException.class, () -> manager.sendMessage("ghost", "指示"),
                "不存在的子代理点名");
        assertThrows(PluginException.class, () -> manager.interrupt("ghost"), "同上（interrupt）");

        String agentId = manager.spawn(parent, "worker", "任务");
        Set<SessionEvent> seen = new HashSet<>();
        awaitCompleted(parent, seen);
        assertThrows(PluginException.class, () -> manager.interrupt(agentId),
                "已空闲的无需中止");
    }
}
