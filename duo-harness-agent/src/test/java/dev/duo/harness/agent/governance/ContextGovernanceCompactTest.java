package dev.duo.harness.agent.governance;

import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.session.Message;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** compaction 折叠用例：超阈值远端折叠为摘要、近端原文保留、协议安全切分、LLM 失败降级。 */
class ContextGovernanceCompactTest {

    private static final String MOCK_SUMMARY = "## 主要请求\n测试摘要";

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ContextGovernanceCompactTest —— compaction：远端折叠与降级（9 用例） ===");
    }

    /** 可编程适配器：stream 返回固定摘要；计数调用次数。 */
    private static final class ScriptedAdapter implements LlmAdapter {
        int calls;
        boolean fail;

        @Override
        public void stream(ChatRequest request, Consumer<ChatChunk> onChunk) {
            calls++;
            if (fail) {
                throw new RuntimeException("模拟 LLM 故障");
            }
            onChunk.accept(new ChatChunk(MOCK_SUMMARY));
        }

        @Override
        public LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink) {
            throw new UnsupportedOperationException("compaction 只用直答");
        }
    }

    /** 组装 n 轮对话投影（每轮 user + assistant 两条，全部 USER 开头的周期结构）。 */
    private static List<Message> rounds(int n) {
        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            messages.add(new Message(Message.Role.USER, "第" + i + "问：" + "x".repeat(200), null, null, null));
            messages.add(new Message(Message.Role.ASSISTANT, "第" + i + "答：" + "y".repeat(200), null, null, null));
        }
        return messages;
    }

    @Test
    void overThresholdFoldsRemoteKeepsRecent() {
        ScriptedAdapter adapter = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(adapter);
        List<Message> messages = rounds(30); // 60 条，约 60 * 50 tokens = 3000 tokens

        List<Message> result = governance.compact(messages, 1_000);

        assertTrue(result.size() < messages.size(), "折叠后条数减少");
        assertEquals(1 + 12, result.size(), "摘要 1 条 + 近端 20%（12 条）");
        assertTrue(result.getFirst().content().contains(MOCK_SUMMARY), "首条为摘要消息");
        assertTrue(result.getFirst().content().contains("主要请求"), "摘要含骨架小节");
        assertEquals(Message.Role.USER, result.getFirst().role(), "摘要以 USER 消息注入");
        // 近端原文保留（最后 12 条 = 第 25-30 轮）
        assertTrue(result.get(1).content().startsWith("第25问"), "近端保留第 25 轮起");
        assertTrue(result.getLast().content().startsWith("第30答"), "近端末条为第 30 轮答");
        assertEquals(1, adapter.calls, "摘要调用一次");
    }

    @Test
    void underThresholdUntouched() {
        ScriptedAdapter adapter = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(adapter);
        List<Message> messages = rounds(5);
        assertEquals(messages, governance.compact(messages, 1_000_000), "未超阈值原样透传");
        assertEquals(0, adapter.calls, "未触发 LLM");
    }

    @Test
    void splitBoundaryNeverStartsWithToolMessage() {
        ScriptedAdapter adapter = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(adapter);
        // 混入 tool 消息的周期：user/assistant/tool 三条一组
        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            messages.add(new Message(Message.Role.USER, "第" + i + "问：" + "x".repeat(200), null, null, null));
            messages.add(new Message(Message.Role.ASSISTANT, "第" + i + "答：" + "y".repeat(200), null, null, null));
            messages.add(Message.tool("call_" + i, "r".repeat(200)));
        }
        List<Message> result = governance.compact(messages, 1_500);
        assertTrue(result.size() > 1, "发生折叠");
        for (Message message : result.subList(1, result.size())) {
            // 近端（摘要之后）允许任意角色，但整体消息序列在摘要后应从 USER 开始（协议周期完整）
            break;
        }
        assertEquals(Message.Role.USER, result.get(1).role(), "切分点推进到 USER 边界");
    }

    @Test
    void autoCompactionLandsCompactionEventOnSession() {
        // 事件化（M19，ADR-0020 决策 6）：govern 触发的压缩落 context/compacted 事件
        // （toolName=auto）——"上下文为何变小"日志可审计；事件化后投影按压缩点拼接，
        // 不再每轮重复总结
        ScriptedAdapter adapter = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(adapter);
        dev.duo.harness.session.Session session =
                dev.duo.harness.session.Session.create(
                        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                                "gov-compact-" + System.nanoTime()));
        try {
            dev.duo.harness.session.SessionEvent.userMessage("占位");
            session.append(dev.duo.harness.session.SessionEvent.userMessage("一问"));
            session.append(dev.duo.harness.session.SessionEvent.assistantMessage("一答"));
            List<Message> messages = rounds(30);
            // 把 rounds 消息折进会话日志（投影重建需要事件形态——直接落 user/assistant 对）
            // 为简化：事件版压缩的输入是任意消息列表，落盘事件与输入解耦
            List<Message> result = governance.compact(messages, 1_000,
                    ContextGovernanceCompactTest.estimate(messages), false, session);

            assertTrue(result.size() < messages.size(), "本轮返回折叠后投影");
            var compacted = session.events().stream()
                    .filter(e -> dev.duo.harness.session.SessionEvent.COMPACTION.equals(e.type()))
                    .findFirst().orElseThrow();
            assertEquals("auto", compacted.toolName(), "预算触发署名 auto");
            assertEquals(MOCK_SUMMARY, compacted.text(), "总结全文随事件落盘");
        } finally {
            session.close();
        }
    }

    private static long estimate(List<Message> messages) {
        return dev.duo.harness.agent.governance.ContextBudget.estimateMessageTokens(messages);
    }

    @Test
    void compactNowForcesManualCompactionAndEchoesResult() {
        // /compact 本体（M19）：不看阈值强制压缩、落 manual 压缩点、返回回显摘要；
        // 近端不足时提示无需压缩、不落事件
        ScriptedAdapter adapter = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(adapter);
        dev.duo.harness.session.Session session =
                dev.duo.harness.session.Session.create(
                        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                                "gov-manual-" + System.nanoTime()));
        try {
            for (int i = 1; i <= 30; i++) {
                session.append(dev.duo.harness.session.SessionEvent.userMessage(
                        "第" + i + "问：" + "x".repeat(200)));
                session.append(dev.duo.harness.session.SessionEvent.assistantMessage(
                        "第" + i + "答：" + "y".repeat(200)));
            }

            String echo = governance.compactNow(session);
            assertTrue(echo.contains("已压缩"), echo);
            var compacted = session.events().stream()
                    .filter(e -> dev.duo.harness.session.SessionEvent.COMPACTION.equals(e.type()))
                    .findFirst().orElseThrow();
            assertEquals("manual", compacted.toolName());

            // 投影按压缩点拼接：替换头 + 近端
            var projected = session.deriveMessages();
            assertTrue(projected.size() < 60, "投影已按压缩点缩小");
            assertTrue(projected.getFirst().content().contains(MOCK_SUMMARY));

            // 防重复总结（事件化的核心收益）：压缩后投影已小，再次 /compact 切分不足
            // 即放弃——不重复调用 LLM、不落重复压缩点
            int callsBefore = adapter.calls;
            String again = governance.compactNow(session);
            assertEquals(callsBefore, adapter.calls, "近端不足不再触发摘要");
            assertTrue(again.contains("无需压缩"), again);
        } finally {
            session.close();
        }
    }

    @Test
    void compactNowWithoutEnoughRemoteSkipsAndExplains() {
        ScriptedAdapter adapter = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(adapter);
        dev.duo.harness.session.Session session =
                dev.duo.harness.session.Session.create(
                        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                                "gov-skip-" + System.nanoTime()));
        try {
            session.append(dev.duo.harness.session.SessionEvent.userMessage("唯一一问"));
            String echo = governance.compactNow(session);
            assertTrue(echo.contains("无需压缩"), echo);
            assertTrue(session.events().stream().noneMatch(e ->
                    dev.duo.harness.session.SessionEvent.COMPACTION.equals(e.type())),
                    "无需压缩不落事件");
            assertEquals(0, adapter.calls, "未触发 LLM");
        } finally {
            session.close();
        }
    }

    @Test
    void toolCallEndingProjectionStillCompacts() {
        // BUG-20260919-01（M19-03 验收实测）：投影尾部为 [助手(工具调用), 工具结果] 收尾
        // 时，切分点向后找 USER 一路推到末尾——被误判"近端不足"永远放弃折叠。修复后
        // 向前回退到最近 USER：压缩照常发生、近端从 USER 开头（协议安全、配对完整）
        ScriptedAdapter adapter = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(adapter);
        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            messages.add(new Message(Message.Role.USER, "第" + i + "问：" + "x".repeat(200), null, null, null));
            messages.add(new Message(Message.Role.ASSISTANT, "第" + i + "答：" + "y".repeat(200), null, null, null));
        }
        // 尾部：工具对收尾（无后续 USER——压缩请求时点的真实形态）
        messages.add(Message.assistantWithToolCalls(
                List.of(new dev.duo.harness.session.ToolCall("call_9", "bash", "{}")), null));
        messages.add(Message.tool("call_9", "r".repeat(200)));

        List<Message> result = governance.compact(messages, 200); // 10 条 ≈ 550 tokens，必超

        assertTrue(result.size() < messages.size(), "工具对收尾的投影照常折叠");
        assertEquals(Message.Role.USER, result.get(1).role(), "近端从 USER 边界开始（协议安全）");
        // 近端应包含完整尾部工具对
        assertEquals(Message.Role.TOOL, result.getLast().role());
    }

    @Test
    void occupancyFallsBackToEstimateAfterCompaction() {
        // 验收反馈修复（M19-03）：压缩点之后的旧实测用量已失效——占用回退本地估算
        // （fromProvider=false，状态面标注"估算"），压缩效果即刻可见
        ScriptedAdapter adapter = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(adapter);
        dev.duo.harness.session.Session session =
                dev.duo.harness.session.Session.create(
                        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                                "gov-occ-" + System.nanoTime()));
        try {
            session.append(dev.duo.harness.session.SessionEvent.userMessage("一问"));
            session.append(dev.duo.harness.session.SessionEvent.assistantMessage("一答",
                    new dev.duo.harness.session.TokenUsage(5_000, 82, 5_082)));
            dev.duo.harness.agent.governance.ContextOccupancy before =
                    governance.occupancy(session);
            assertTrue(before.fromProvider(), "压缩前实测口径");
            assertEquals(5_082, before.tokens());

            session.append(dev.duo.harness.session.SessionEvent.compaction("历史总结", "manual"));
            dev.duo.harness.agent.governance.ContextOccupancy after = governance.occupancy(session);
            assertFalse(after.fromProvider(), "压缩点使旧实测失效——回退估算口径");
            assertTrue(after.tokens() < 5_082, "占用即刻反映压缩效果: " + after.tokens());
        } finally {
            session.close();
        }
    }

    @Test
    void llmFailurePassesThroughUnchanged() {
        ScriptedAdapter adapter = new ScriptedAdapter();
        adapter.fail = true;
        ContextGovernance governance = new ContextGovernance(adapter);
        List<Message> messages = rounds(30);
        assertEquals(messages, governance.compact(messages, 1_000),
                "LLM 失败降级：原样透出（治理永不冒险丢上下文）");
    }
}
