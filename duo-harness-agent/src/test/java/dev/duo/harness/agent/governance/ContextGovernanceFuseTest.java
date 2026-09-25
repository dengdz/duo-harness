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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩熔断用例（M25 工单 05）：连续失败达阈值熔断（自动压缩暂停、会话照常）、
 * 状态经 occupancy 可见、成功清零恢复、manual /compact 不受限、熔断态 microcompact
 * 照常（并存互不干扰）、裁剪点作废旧 usage 计量。
 */
class ContextGovernanceFuseTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ContextGovernanceFuseTest —— 压缩熔断：触发、状态可见、"
                + "清零恢复、manual 不受限、micro 不受牵连（5 用例） ===");
    }

    @TempDir
    Path tempDir;

    /** 可切换适配器：fail = 抛异常模拟 summary 失败；calls 计数。 */
    private static final class ScriptedAdapter implements LlmAdapter {
        int calls;
        boolean fail = true;

        @Override
        public void stream(ChatRequest request, Consumer<ChatChunk> onChunk) {
            calls++;
            if (fail) {
                throw new RuntimeException("模拟 LLM 故障");
            }
            onChunk.accept(new ChatChunk("## 主要请求\n摘要"));
        }

        @Override
        public LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink) {
            throw new UnsupportedOperationException("治理只用直答");
        }
    }

    /**
     * 组装 6 组短结果对话 + 大额 usage：结果过短 micro 无料可清（minSaving 放弃），
     * compaction 以 usage 口径出手——熔断测试的夹具（熔断防的就是这条路径的循环失败）。
     */
    private static Session shortSession(Path dir) throws IOException {
        Session session = Session.create(dir.resolve("sessions"));
        for (int i = 1; i <= 6; i++) {
            session.append(SessionEvent.userMessage("问题" + i));
            session.append(SessionEvent.toolCall("c" + i, "read", "{}"));
            session.append(SessionEvent.toolResult("c" + i, "read", "短结果"));
        }
        session.append(SessionEvent.assistantMessage(
                "回答", new TokenUsage(110_000, 500, 110_500)));
        return session;
    }

    /** 组装 6 组长结果对话（micro 有料可清），末尾大额 usage。 */
    private static Session longSession(Path dir) throws IOException {
        Session session = Session.create(dir.resolve("sessions"));
        for (int i = 1; i <= 6; i++) {
            session.append(SessionEvent.userMessage("问题" + i));
            session.append(SessionEvent.toolCall("c" + i, "read", "{}"));
            session.append(SessionEvent.toolResult("c" + i, "read", "r".repeat(2100)));
        }
        session.append(SessionEvent.assistantMessage(
                "回答", new TokenUsage(110_000, 500, 110_500)));
        return session;
    }

    @Test
    void 连续失败达阈值熔断且会话照常() throws IOException {
        Session session = shortSession(tempDir);
        ScriptedAdapter llm = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(llm, null);

        for (int i = 0; i < ContextGovernance.COMPACTION_FAILURE_TRIP_THRESHOLD; i++) {
            governance.govern(session.deriveMessages(), session);
        }
        int callsAfterTrip = llm.calls;
        assertEquals(ContextGovernance.COMPACTION_FAILURE_TRIP_THRESHOLD, callsAfterTrip,
                "熔断前每次都尝试");
        assertTrue(governance.occupancy(session).compactionTripped(), "熔断态经 occupancy 可见");

        // 熔断后：自动压缩静默跳过（不再烧调用），会话照常出投影
        var governed = governance.govern(session.deriveMessages(), session);
        assertTrue(governed != null && !governed.isEmpty(), "熔断后会话照常出投影");
        assertEquals(callsAfterTrip, llm.calls, "熔断后不再尝试 summary");
        session.close();
    }

    @Test
    void 成功压缩清零恢复() throws IOException {
        Session session = shortSession(tempDir);
        ScriptedAdapter llm = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(llm, null);

        governance.govern(session.deriveMessages(), session);
        governance.govern(session.deriveMessages(), session);
        llm.fail = false;  // 修好：下一次成功压缩清零并解除熔断
        governance.govern(session.deriveMessages(), session);
        assertFalse(governance.occupancy(session).compactionTripped(), "成功清零——未熔断");

        // 再坏：从零重新计数。压缩点后投影已塌缩为摘要——补足新历史（新 user + 长结果）
        // 让 compaction 重新可达，单次失败只计 1/3 不熔断
        llm.fail = true;
        session.append(SessionEvent.userMessage("压缩后新问题"));
        session.append(SessionEvent.toolCall("new", "read", "{}"));
        session.append(SessionEvent.toolResult("new", "read", "r".repeat(2100)));
        session.append(SessionEvent.assistantMessage(
                "新回答", new TokenUsage(110_000, 500, 110_500)));
        governance.govern(session.deriveMessages(), session);
        assertFalse(governance.occupancy(session).compactionTripped(),
                "清零后重新计数——单次失败不再熔断");
        session.close();
    }

    @Test
    void 熔断后manual压缩不受限且成功清零() throws IOException {
        Session session = shortSession(tempDir);
        ScriptedAdapter llm = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(llm, null);
        for (int i = 0; i < ContextGovernance.COMPACTION_FAILURE_TRIP_THRESHOLD; i++) {
            governance.govern(session.deriveMessages(), session);
        }
        llm.fail = false;
        int before = llm.calls;
        String echo = governance.compactNow(session);
        assertEquals(before + 1, llm.calls, "manual /compact 不受熔断约束");
        assertTrue(echo.contains("已压缩"), "手动压缩成功: " + echo);
        assertFalse(governance.occupancy(session).compactionTripped(), "成功清零恢复");
        session.close();
    }

    @Test
    void 熔断态microcompact照常裁剪() throws IOException {
        // 并存互不干扰（用户故事 14）：熔断只挡 summary——micro 裁剪照常工作。
        // 短会话熔断后新来一组长结果：micro 有料即清，summary 仍被熔断压制
        Session session = shortSession(tempDir);
        ScriptedAdapter llm = new ScriptedAdapter();
        // keepRecent=2（与 04 人工触发验收同款配置）：新组两轮后即落保留窗口外
        ContextGovernance governance = new ContextGovernance(llm, new ContextGovernance.Tuning(
                null, null, null, null, null, null, true, 2));
        for (int i = 0; i < ContextGovernance.COMPACTION_FAILURE_TRIP_THRESHOLD; i++) {
            governance.govern(session.deriveMessages(), session);
        }
        int callsAtTrip = llm.calls;

        session.append(SessionEvent.userMessage("新问题：读个大文件"));
        session.append(SessionEvent.toolCall("big", "read", "{}"));
        session.append(SessionEvent.toolResult("big", "read", "r".repeat(2100)));
        session.append(SessionEvent.userMessage("继续"));
        session.append(SessionEvent.userMessage("再继续"));  // 推移窗口：big 组落到保留区外
        governance.govern(session.deriveMessages(), session);

        assertTrue(session.events().stream()
                        .anyMatch(e -> SessionEvent.MICROCOMPACT.equals(e.type())),
                "熔断态 micro 照常裁剪（落裁剪点事件）");
        assertEquals(callsAtTrip, llm.calls, "summary 仍被熔断——两机制互不牵连");
        session.close();
    }

    @Test
    void 裁剪点作废旧usage计量() throws IOException {
        // 04 留的计量语义复核：micro 裁剪点之后、新实测落盘之前，旧 usage 反映裁剪前
        // 口径——occupancy 回退本地估算（fromProvider=false），防按虚高占用误触发
        Session session = longSession(tempDir);
        ScriptedAdapter llm = new ScriptedAdapter();
        ContextGovernance governance = new ContextGovernance(llm, null);
        assertTrue(governance.occupancy(session).fromProvider(),
                "裁剪前实测口径");

        governance.govern(session.deriveMessages(), session);  // 落裁剪点

        assertFalse(governance.occupancy(session).fromProvider(),
                "裁剪点作废旧 usage——回退本地估算口径");
        session.close();
    }
}
