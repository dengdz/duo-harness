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
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * microcompact 治理挂点用例（M25 工单 04）：逼近窗口触发本地裁剪（事件落盘 +
 * 本轮投影替换 + 最近组保留）、开关关闭不触发、生效后压缩计量切本地估算（不重复
 * 触发 summary 压缩）。
 */
class ContextGovernanceMicrocompactTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ContextGovernanceMicrocompactTest —— 治理挂点：触发、"
                + "事件痕、开关、压缩计量隔离（3 用例） ===");
    }

    @TempDir
    Path tempDir;

    /** 直答适配器（仅 compaction 摘要会用；计数调用证明 micro 清够时不再烧 summary）。 */
    private static final class ScriptedAdapter implements LlmAdapter {
        int calls;

        @Override
        public void stream(ChatRequest request, Consumer<ChatChunk> onChunk) {
            calls++;
            onChunk.accept(new ChatChunk("## 主要请求\n摘要"));
        }

        @Override
        public LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink) {
            throw new UnsupportedOperationException("治理只用直答");
        }
    }

    /** 组装 6 组对话（每组：user + bash 调用 + 长结果），末组带大额 usage 事件。 */
    private static Session longSession(Path dir) throws IOException {
        Session session = Session.create(dir.resolve("sessions"));
        for (int i = 1; i <= 6; i++) {
            session.append(SessionEvent.userMessage("问题" + i));
            session.append(SessionEvent.toolCall("c" + i, "bash", "{}"));
            session.append(SessionEvent.toolResult("c" + i, "bash", "r".repeat(2100)));
        }
        session.append(SessionEvent.assistantMessage(
                "回答", new TokenUsage(100_000, 500, 100_500)));
        return session;
    }

    @Test
    void 逼近窗口触发裁剪落事件并替换投影() throws IOException {
        Session session = longSession(tempDir);
        ScriptedAdapter llm = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(llm, null);

        List<Message> governed = governance.govern(session.deriveMessages(), session);

        SessionEvent microEvent = session.events().stream()
                .filter(e -> SessionEvent.MICROCOMPACT.equals(e.type()))
                .findFirst().orElseThrow(() -> new AssertionError("未落裁剪点事件"));
        var cleared = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(microEvent.text()).get("cleared");
        assertEquals(1, cleared.size(), "只清保留窗口外的最早组: " + microEvent.text());
        assertEquals("c1", cleared.get(0).asText(), "名单 = 最早组的结果");

        assertTrue(governed.stream()
                        .filter(m -> "c1".equals(m.toolCallId())).findFirst().orElseThrow()
                        .content().contains("已清除"),
                "本轮投影立即替换（后续轮经投影层复现）");
        assertEquals("r".repeat(2100), governed.stream()
                        .filter(m -> "c6".equals(m.toolCallId())).findFirst().orElseThrow().content(),
                "最近 5 组内结果完整保留");
        assertEquals(0, llm.calls, "micro 清后估算远低于阈值——不再触发 summary 压缩");
        session.close();
    }

    @Test
    void 开关关闭不触发() throws IOException {
        Session session = longSession(tempDir);
        ContextGovernance governance = new ContextGovernance(new ScriptedAdapter(),
                new ContextGovernance.Tuning(
                        null, null, null, null, null, null, false, null));

        governance.govern(session.deriveMessages(), session);

        assertTrue(session.events().stream()
                        .noneMatch(e -> SessionEvent.MICROCOMPACT.equals(e.type())),
                "microcompactEnabled=false 零裁剪");
        session.close();
    }

    @Test
    void 结果过短无足够节省不落裁剪痕且压缩照常() throws IOException {
        // minSaving 放弃裁剪 ≠ 放弃治理：micro 不落痕不替换，compaction 以
        // usage 口径照常触发（05 触发关系的基线行为——裁剪没帮上忙就烧 summary）
        Session session = Session.create(tempDir.resolve("sessions"));
        for (int i = 1; i <= 6; i++) {
            session.append(SessionEvent.userMessage("问题" + i));
            session.append(SessionEvent.toolCall("c" + i, "bash", "{}"));
            session.append(SessionEvent.toolResult("c" + i, "bash", "短结果"));
        }
        // usage 须超压缩阈值（102400）——micro 阈值（92160）早已越过而裁剪无料可清，
        // 验证此区间内 compaction 兜底照常
        session.append(SessionEvent.assistantMessage(
                "回答", new TokenUsage(110_000, 500, 110_500)));
        ScriptedAdapter llm = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(llm, null);

        List<Message> governed = governance.govern(session.deriveMessages(), session);

        assertTrue(session.events().stream()
                        .noneMatch(e -> SessionEvent.MICROCOMPACT.equals(e.type())),
                "低于最小节省——不落裁剪痕");
        assertEquals(1, llm.calls, "裁剪没帮上忙——compaction 照常发起 summary");
        assertTrue(session.events().stream()
                        .anyMatch(e -> SessionEvent.COMPACTION.equals(e.type())),
                "压缩点照常落盘");
        // compaction 触发后本轮投影折叠为摘要单条（microcompact 未动任何结果——
        // 日志里 tool/result 原文短结果原样在册）
        assertEquals(1, governed.size(), "投影折叠为压缩摘要");
        assertTrue(session.events().stream()
                        .filter(e -> "c1".equals(e.toolCallId())
                                && SessionEvent.TOOL_RESULT.equals(e.type()))
                        .findFirst().orElseThrow().text().equals("短结果"),
                "日志原文不受影响");
        session.close();
    }

}
