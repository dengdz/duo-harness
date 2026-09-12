package dev.duo.harness.agent.internal;

import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.session.Message;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ToolCallingAgent 直答路径用例：会话写入、投影请求、回调序列、AgentReply 组装。 */
class ToolCallingAgentTest {

    @TempDir
    Path tempDir;

    private Session newSession() throws IOException {
        return Session.create(tempDir.resolve("sessions"));
    }

    private LlmAdapter scriptedAdapter(String replyText) {
        return new LlmAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                onChunk.accept(new ChatChunk(replyText));
            }
        };
    }

    @Test
    void directAnswerWritesSessionAndReturnsReply() throws IOException {
        Session session = newSession();
        ToolCallingAgent agent = new ToolCallingAgent(scriptedAdapter("你好呀"), session,
                "你是助手", 10);

        AgentReply reply = agent.send("你好", AgentListener.NONE);

        assertEquals("你好呀", reply.finalText());
        assertTrue(reply.completed());
        assertTrue(reply.toolInvocations().isEmpty(), "直答路径无工具调用");
        // 会话记录：user/message + assistant/message 各一条
        assertEquals(2, session.events().size());
        assertEquals(SessionEvent.USER_MESSAGE, session.events().get(0).type());
        assertEquals("你好", session.events().get(0).text());
        assertEquals(SessionEvent.ASSISTANT_MESSAGE, session.events().get(1).type());
    }

    @Test
    void listenerReceivesChunkSequence() throws IOException {
        Session session = newSession();
        List<String> chunks = new ArrayList<>();
        LlmAdapter multi = new LlmAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                onChunk.accept(new ChatChunk("第一段"));
                onChunk.accept(new ChatChunk("第二段"));
            }
        };
        ToolCallingAgent agent = new ToolCallingAgent(multi, session, "你是助手", 10);

        agent.send("问", new AgentListener() {
            @Override
            public void onChunk(String text) {
                chunks.add(text);
            }
        });

        assertEquals(List.of("第一段", "第二段"), chunks, "chunk 按序交付给 listener");
    }

    @Test
    void requestCarriesProjectionHistoryAndSystem() throws IOException {
        Session session = newSession();
        List<ChatRequest> captured = new ArrayList<>();
        LlmAdapter capturing = new LlmAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                captured.add(request);
                onChunk.accept(new ChatChunk("ok"));
            }
        };
        ToolCallingAgent agent = new ToolCallingAgent(capturing, session, "你是助手", 10);

        agent.send("第一问", AgentListener.NONE);
        agent.send("第二问", AgentListener.NONE);

        // 第二轮请求应投影第一轮历史（多轮记忆经循环生效）
        assertEquals(2, captured.size());
        ChatRequest second = captured.get(1);
        assertEquals("你是助手", second.systemPrompt());
        assertEquals(3, second.messages().size(), "两轮投影: u + a + u");
        assertEquals(ChatMessage.Role.USER, second.messages().get(0).role(),
                "请求消息应为 llm 契约形态");
        assertEquals("第一问", second.messages().get(0).content());
        assertEquals(ChatMessage.Role.ASSISTANT, second.messages().get(1).role());
        assertEquals("第二问", second.messages().get(2).content());
        assertTrue(second.tools().isEmpty(), "工单 01 骨架无工具清单");
    }

    @Test
    void invalidConstructorArgsRejected() throws IOException {
        Session session = newSession();
        LlmAdapter adapter = scriptedAdapter("ok");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ToolCallingAgent(adapter, session, "  ", 10),
                "空 systemPrompt 应被拒绝");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ToolCallingAgent(adapter, session, "你是助手", 0),
                "迭代上限至少为 1");
    }
}
