package dev.duo.harness.agent.internal;

import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.prompt.PromptRegistry;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 思考完成态事件面（M29 工单 06）：assistant/message 携带 reasoning 的两态、空串归一与投影零回传。 */
class ToolCallingAgentReasoningTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ToolCallingAgentReasoningTest —— 思考事件面：两态携带、投影零回传、流式增量窗口（4 用例） ===");
    }

    /** 可编程适配器：按预设 LlmTurn 直答（每轮同一回答），可选思考增量序列，捕获每次请求。 */
    private static final class ScriptedAdapter implements LlmAdapter {
        final List<ChatRequest> requests = new ArrayList<>();
        private final LlmTurn turn;
        private final List<String> reasoningDeltas;

        ScriptedAdapter(LlmTurn turn) {
            this(turn, List.of());
        }

        ScriptedAdapter(LlmTurn turn, List<String> reasoningDeltas) {
            this.turn = turn;
            this.reasoningDeltas = reasoningDeltas;
        }

        @Override
        public void stream(ChatRequest request, Consumer<ChatChunk> onChunk) {
            onChunk.accept(new ChatChunk(turn.text()));
        }

        @Override
        public LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink) {
            return streamTurn(request, textSink, r -> { });
        }

        @Override
        public LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink,
                                  Consumer<String> reasoningSink) {
            requests.add(request);
            for (String delta : reasoningDeltas) {
                reasoningSink.accept(delta);
            }
            textSink.accept(turn.text());
            return turn;
        }
    }

    private SessionEvent lastAssistant(Session session) {
        return session.events().stream()
                .filter(e -> SessionEvent.ASSISTANT_MESSAGE.equals(e.type()))
                .reduce((a, b) -> b)
                .orElseThrow();
    }

    @Test
    void 思考模型完成态随消息携带() throws IOException {
        Session session = Session.create(tempDir.resolve("thinking"));
        ScriptedAdapter adapter = new ScriptedAdapter(new LlmTurn("好的", List.of(), "先核对用户意图，再组织回答……"));
        ToolCallingAgent agent = new ToolCallingAgent(adapter, noTools(), session, new PromptRegistry("测试提示"));

        agent.send("第一问", listener());

        SessionEvent assistant = lastAssistant(session);
        assertEquals("先核对用户意图，再组织回答……", assistant.reasoning(), "思考内容随 assistant/message 持久化");
        assertEquals("好的", assistant.text(), "正文不受影响");
    }

    @Test
    void 非思考模型不携带零变化() throws IOException {
        Session session = Session.create(tempDir.resolve("plain"));
        ScriptedAdapter adapter = new ScriptedAdapter(new LlmTurn("好的", List.of(), null));
        ToolCallingAgent agent = new ToolCallingAgent(adapter, noTools(), session, new PromptRegistry("测试提示"));

        agent.send("第一问", listener());

        assertNull(lastAssistant(session).reasoning(), "非思考模型事件不带 reasoning 字段值（null）");
        assertNotNull(lastAssistant(session).text());
    }

    @Test
    void 空串归一为null且投影零回传() throws IOException {
        assertNull(SessionEvent.assistantMessage("答", "   ", null).reasoning(),
                "空白思考归一为 null（无思考不携带的不变量在工厂收敛）");

        // 思考会话第二轮请求的投影历史：assistant 消息不回传完成态思考（投影重建只取 text）
        Session session = Session.create(tempDir.resolve("projection"));
        ScriptedAdapter adapter = new ScriptedAdapter(new LlmTurn("好的", List.of(), "第一轮思考……"));
        ToolCallingAgent agent = new ToolCallingAgent(adapter, noTools(), session, new PromptRegistry("测试提示"));
        agent.send("第一问", listener());
        agent.send("第二问", listener());

        assertTrue(adapter.requests.size() >= 2, "两轮请求都已发出");
        // 第二轮请求发出时点，投影历史含第一轮 assistant 消息（其事件已带 reasoning）——
        // 请求历史里该消息的 reasoningContent 必须为 null：完成态思考不回传
        List<ChatMessage> assistantMsgs = adapter.requests.getLast().messages().stream()
                .filter(m -> m.role() == ChatMessage.Role.ASSISTANT)
                .toList();
        assertEquals("好的", assistantMsgs.getFirst().content(), "投影历史含第一轮助手回答");
        assertTrue(assistantMsgs.stream().allMatch(m -> m.reasoningContent() == null),
                "完成态思考不进请求历史（零回传语义——回传歧义在投影点免疫）");
    }

    @Test
    void 思考流式增量按窗口落盘且不入投影() throws IOException {
        Session session = Session.create(tempDir.resolve("streaming"));
        // 3 段增量共 30 字符 < 256 窗口——残留不 flush（收口全文权威），故零事件；
        // 用长增量跨窗口触发 flush
        String big = "思".repeat(300);
        ScriptedAdapter adapter = new ScriptedAdapter(new LlmTurn("好的", List.of(), big), List.of(big));
        ToolCallingAgent agent = new ToolCallingAgent(adapter, noTools(), session, new PromptRegistry("测试提示"));

        agent.send("第一问", listener());

        List<SessionEvent> reasoningEvents = session.events().stream()
                .filter(e -> SessionEvent.ASSISTANT_REASONING.equals(e.type()))
                .toList();
        assertEquals(1, reasoningEvents.size(), "300 字符增量跨过 256 窗口 flush 一条事件");
        assertEquals(big, reasoningEvents.getFirst().text(), "事件携带增量原文");
        // 增量事件不入投影（思考不进请求历史）：投影消息数不含思考
        long assistantCount = session.deriveMessages().stream()
                .filter(m -> m.role() == dev.duo.harness.session.Message.Role.ASSISTANT)
                .count();
        assertEquals(1, assistantCount, "增量思考事件零投影——投影只有收口的 assistant 消息");
        // 短增量残留不 flush：窗口内残留由收口全文覆盖
        Session session2 = Session.create(tempDir.resolve("streaming-residual"));
        ScriptedAdapter adapter2 = new ScriptedAdapter(new LlmTurn("好的", List.of(), "短思考"),
                List.of("短"));
        new ToolCallingAgent(adapter2, noTools(), session2, new PromptRegistry("测试提示")).send("问", listener());
        assertEquals(0, session2.events().stream()
                        .filter(e -> SessionEvent.ASSISTANT_REASONING.equals(e.type())).count(),
                "窗口内残留不 flush（收口 assistant/message 的 reasoning 全文为权威形态）");
    }

    private static AgentListener listener() {
        return new AgentListener() {
            @Override
            public void onChunk(String text) {
            }

            @Override
            public void onToolCall(String toolName, String argumentsJson) {
            }

            @Override
            public void onToolResult(String toolName, String resultText, boolean isError) {
            }
        };
    }

    private static dev.duo.harness.tools.ToolsService noTools() {
        return new dev.duo.harness.tools.ToolsService() {
            @Override
            public dev.duo.harness.core.api.Disposable register(
                    dev.duo.harness.core.api.Context registrant, dev.duo.harness.tools.ToolDefinition definition) {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.duo.harness.core.api.Disposable guard(
                    dev.duo.harness.core.api.Context registrant, dev.duo.harness.tools.GuardCheck check) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<dev.duo.harness.tools.ToolDefinition> list() {
                return List.of();
            }

            @Override
            public dev.duo.harness.tools.ToolResult execute(String toolName, com.fasterxml.jackson.databind.JsonNode args) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
