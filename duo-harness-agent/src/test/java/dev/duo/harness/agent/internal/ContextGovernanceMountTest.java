package dev.duo.harness.agent.internal;

import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.ContextGovernance;
import dev.duo.harness.agent.PromptRegistry;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.session.Message;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 上下文治理挂载用例：空管线零行为变化（投影逐条一致），计量基座随请求构造生效。 */
class ContextGovernanceMountTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ContextGovernanceMountTest —— 治理挂载：空管线零行为变化（1 用例） ===");
    }

    /** 捕获请求的适配器：直答单段返回。 */
    private static final class RequestCapturingAdapter implements LlmAdapter {
        final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public void stream(dev.duo.harness.llm.ChatRequest request,
                           java.util.function.Consumer<ChatChunk> onChunk) {
            onChunk.accept(new ChatChunk("ok"));
        }

        @Override
        public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
            requests.add(request);
            textSink.accept("好的");
            return new LlmTurn("好的", List.of(), null);
        }
    }

    @Test    void emptyPipelineKeepsProjectionUnchanged() throws IOException {
        PromptRegistry prompts = new PromptRegistry("测试提示");
        // 两个相同历史的会话：一个无治理基线，一个空管线治理
        Session baselineSession = identicalSession();
        Session governedSession = identicalSession();

        RequestCapturingAdapter baselineAdapter = new RequestCapturingAdapter();
        ToolCallingAgent baseline = new ToolCallingAgent(baselineAdapter, noTools(), baselineSession, prompts);
        baseline.send("第三问", listener());
        ChatRequest baselineRequest = baselineAdapter.requests.getLast();

        RequestCapturingAdapter governedAdapter = new RequestCapturingAdapter();
        ToolCallingAgent governed = new ToolCallingAgent(governedAdapter, noTools(), governedSession, prompts,
                10, new ContextGovernance(governedAdapter));
        governed.send("第三问", listener());
        ChatRequest governedRequest = governedAdapter.requests.getLast();

        assertNotNull(governedRequest);
        assertEquals(baselineRequest.messages().size(), governedRequest.messages().size(),
                "空管线不改变消息条数");
        for (int i = 0; i < baselineRequest.messages().size(); i++) {
            assertEquals(baselineRequest.messages().get(i).content(),
                    governedRequest.messages().get(i).content(),
                    "空管线不改变第 " + i + " 条消息内容");
        }
        // 会话日志不受治理影响（第一性约束）
        List<Message> projection = governedSession.deriveMessages();
        assertEquals(5, projection.size(), "send 后投影 = 原有 3 条 + 本轮 user 与 assistant");
        assertEquals("第三问", projection.get(3).content(), "倒数第二条为本轮用户消息");
        assertEquals("好的", projection.getLast().content(), "最后一条为助手回复");
    }

    /** 组装相同历史的会话（两问一答；第三问由 send 写入）。 */
    private Session identicalSession() throws IOException {
        Session session = Session.create(tempDir.resolve("sessions"));
        session.append(SessionEvent.userMessage("第一问"));
        session.append(SessionEvent.assistantMessage("第一答"));
        session.append(SessionEvent.userMessage("第二问"));
        return session;
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
