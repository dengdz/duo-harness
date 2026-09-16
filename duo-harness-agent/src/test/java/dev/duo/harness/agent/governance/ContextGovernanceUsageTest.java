package dev.duo.harness.agent.governance;

import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.session.Message;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.session.TokenUsage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 治理计量双路径用例（ADR-0009）：provider 真实用量优先——估算超阈而实测未超不折叠、
 * 实测超阈而估算未超也折叠；事件缺失时估算兜底，治理不因 provider 不报而失效。
 */
class ContextGovernanceUsageTest {

    /** compaction 触发阈值（0.8 × 128K 窗口）。 */
    private static final long THRESHOLD = (long) (ContextGovernance.COMPACTION_THRESHOLD_RATIO
            * ContextGovernance.CONTEXT_WINDOW_TOKENS);

    @TempDir
    Path tempDir;

    private static final String MOCK_SUMMARY = "## 主要请求\n测试摘要";

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ContextGovernanceUsageTest —— 计量双路径：真实值优先、估算兜底（3 用例） ===");
    }

    /** 可编程适配器：stream 返回固定摘要；计数调用次数。 */
    private static final class ScriptedAdapter implements LlmAdapter {
        int calls;

        @Override
        public void stream(ChatRequest request, Consumer<ChatChunk> onChunk) {
            calls++;
            onChunk.accept(new ChatChunk(MOCK_SUMMARY));
        }

        @Override
        public LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink) {
            throw new UnsupportedOperationException("compaction 只用直答");
        }
    }

    /** 组装 n 轮小对话投影（user/assistant 周期，估算体量远低于阈值）。 */
    private static List<Message> smallRounds(int rounds) {
        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= rounds; i++) {
            messages.add(new Message(Message.Role.USER, "第" + i + "问：" + "x".repeat(200), null, null, null));
            messages.add(new Message(Message.Role.ASSISTANT, "第" + i + "答：" + "y".repeat(200), null, null, null));
        }
        return messages;
    }

    /** 组装 n 条大消息（估算体量超阈值——每条约 7.5K tokens）。 */
    private static List<Message> bigMessages(int count) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(new Message(Message.Role.USER, "第" + i + "条：" + "x".repeat(30_000), null, null, null));
        }
        return messages;
    }

    private Session newSession() throws IOException {
        return Session.create(tempDir.resolve("sessions"));
    }

    @Test
    void realUsageTriggersCompactionDespiteSmallEstimate() throws IOException {
        // 实测 114K 超阈（102.4K）而估算仅数百——折叠必须发生，证明真实值覆盖估算
        Session session = newSession();
        session.append(SessionEvent.userMessage("问"));
        session.append(SessionEvent.assistantMessage("答", new TokenUsage(112_000, 2_000, 114_000)));
        ScriptedAdapter adapter = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(adapter);
        List<Message> messages = smallRounds(4);

        List<Message> result = governance.govern(messages, session);

        assertTrue(result.size() < messages.size(), "实测超阈触发折叠: " + result.size());
        assertEquals(1, adapter.calls, "摘要调用一次");
        assertTrue(result.getFirst().content().contains(MOCK_SUMMARY), "首条为摘要消息");
    }

    @Test
    void realUsageBelowThresholdPreventsCompactionDespiteLargeEstimate() throws IOException {
        // 实测 600 远低于阈而估算约 150K——估算字符数的高估不再误触发折叠
        Session session = newSession();
        session.append(SessionEvent.userMessage("问"));
        session.append(SessionEvent.assistantMessage("答", new TokenUsage(500, 100, 600)));
        ScriptedAdapter adapter = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(adapter);
        List<Message> messages = bigMessages(20);

        List<Message> result = governance.govern(messages, session);

        assertEquals(messages, result, "实测未超阈：估算再大也不折叠");
        assertEquals(0, adapter.calls, "未触发 LLM");
    }

    @Test
    void estimateFallbackTriggersWhenNoUsageRecorded() throws IOException {
        // 事件无 usage（provider 未报告/首轮）：估算兜底，治理照常工作
        Session session = newSession();
        session.append(SessionEvent.userMessage("第一问"));
        session.append(SessionEvent.assistantMessage("第一答"));
        ScriptedAdapter adapter = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(adapter);
        List<Message> messages = bigMessages(20);

        List<Message> result = governance.govern(messages, session);

        assertTrue(result.size() < messages.size(), "无实测数据时估算兜底触发折叠");
        assertEquals(1, adapter.calls, "摘要调用一次");
    }

    @Test
    void occupancyReportsProviderTokensWithThreshold() throws IOException {
        // 状态面数据源（工单 M10-07）：与 compaction 判定同源同口径
        Session session = newSession();
        session.append(SessionEvent.userMessage("问"));
        session.append(SessionEvent.assistantMessage("答", new TokenUsage(112_000, 2_000, 114_000)));
        ContextGovernance governance = new ContextGovernance(new ScriptedAdapter());

        ContextOccupancy occupancy = governance.occupancy(session);

        assertEquals(114_000, occupancy.tokens(), "实测口径：prompt + completion");
        assertTrue(occupancy.fromProvider(), "实测标记");
        assertEquals(THRESHOLD, occupancy.thresholdTokens(), "阈值与 compaction 判定同源");
        assertEquals(ContextGovernance.CONTEXT_WINDOW_TOKENS, occupancy.windowTokens());
    }

    @Test
    void occupancyFallsBackToEstimateWithoutUsage() throws IOException {
        Session session = newSession();
        session.append(SessionEvent.userMessage("x".repeat(400)));
        ContextGovernance governance = new ContextGovernance(new ScriptedAdapter());

        ContextOccupancy occupancy = governance.occupancy(session);

        assertEquals(100, occupancy.tokens(), "400 字符 ÷ 4 = 100 tokens（估算兜底）");
        assertEquals(false, occupancy.fromProvider(), "估算口径标记（展示须标注，不冒充实测）");
    }
}
