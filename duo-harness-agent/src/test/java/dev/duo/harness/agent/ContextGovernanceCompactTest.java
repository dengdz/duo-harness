package dev.duo.harness.agent;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** compaction 折叠用例：超阈值远端折叠为摘要、近端原文保留、协议安全切分、LLM 失败降级。 */
class ContextGovernanceCompactTest {

    private static final String MOCK_SUMMARY = "## 主要请求\n测试摘要";

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ContextGovernanceCompactTest —— compaction：远端折叠与降级（4 用例） ===");
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
    void llmFailurePassesThroughUnchanged() {
        ScriptedAdapter adapter = new ScriptedAdapter();
        adapter.fail = true;
        ContextGovernance governance = new ContextGovernance(adapter);
        List<Message> messages = rounds(30);
        assertEquals(messages, governance.compact(messages, 1_000),
                "LLM 失败降级：原样透出（治理永不冒险丢上下文）");
    }
}
