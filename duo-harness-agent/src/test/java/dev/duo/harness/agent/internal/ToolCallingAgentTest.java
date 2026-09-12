package dev.duo.harness.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.PromptRegistry;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.session.Message;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话投影→llm 消息视图（直答测试用）。 */
interface ToolsView {
    ToolsService tools();
}

/** ToolCallingAgent 直答路径用例：会话写入、投影请求、回调序列、AgentReply 组装。 */
class ToolCallingAgentTest {

    @TempDir
    Path tempDir;

    private Session newSession() throws IOException {
        return Session.create(tempDir.resolve("sessions"));
    }

    /** 空工具域（直答路径不触工具，execute 不会被调）。 */
    private ToolsService noTools() {
        return new ToolsService() {
            @Override
            public dev.duo.harness.core.api.Disposable register(
                    dev.duo.harness.core.api.Context registrant, dev.duo.harness.tools.ToolDefinition definition) {
                throw new UnsupportedOperationException("直答路径不应注册工具");
            }

            @Override
            public dev.duo.harness.core.api.Disposable guard(
                    dev.duo.harness.core.api.Context registrant, dev.duo.harness.tools.GuardCheck check) {
                throw new UnsupportedOperationException("直答路径不应注册 guard");
            }

            @Override
            public List<dev.duo.harness.tools.ToolDefinition> list() {
                return List.of();
            }

            @Override
            public ToolResult execute(String toolName, JsonNode args) {
                throw new UnsupportedOperationException("直答路径不应执行工具");
            }
        };
    }

    /** 只实现 stream 的适配器：streamTurn 委托回 stream（单段聚合）。 */
    private abstract static class StreamOnlyAdapter implements LlmAdapter {
        @Override
        public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
            List<String> chunks = new ArrayList<>();
            stream(request, onChunk -> {
                chunks.add(onChunk.text());
                textSink.accept(onChunk.text());
            });
            return new LlmTurn(String.join("", chunks), List.of());
        }
    }

    private LlmAdapter scriptedAdapter(String replyText) {
        return new StreamOnlyAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                onChunk.accept(new ChatChunk(replyText));
            }
        };
    }

    @Test
    void directAnswerWritesSessionAndReturnsReply() throws IOException {
        Session session = newSession();
        ToolCallingAgent agent = new ToolCallingAgent(scriptedAdapter("你好呀"), noTools(), session,
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
        LlmAdapter multi = new StreamOnlyAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                onChunk.accept(new ChatChunk("第一段"));
                onChunk.accept(new ChatChunk("第二段"));
            }
        };
        ToolCallingAgent agent = new ToolCallingAgent(multi, noTools(), session, "你是助手", 10);

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
        LlmAdapter capturing = new StreamOnlyAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                captured.add(request);
                onChunk.accept(new ChatChunk("ok"));
            }
        };
        ToolCallingAgent agent = new ToolCallingAgent(capturing, noTools(), session, "你是助手", 10);

        agent.send("第一问", AgentListener.NONE);
        agent.send("第二问", AgentListener.NONE);

        // 第二轮请求应投影第一轮历史（多轮记忆经循环生效）
        assertEquals(2, captured.size());
        ChatRequest second = captured.get(1);
        assertEquals("你是助手", second.systemPrompt());
        assertEquals(3, second.messages().size(), "两轮投影: u + a + u");
        assertEquals(ChatMessage.Role.USER, second.messages().get(0).role());
        assertEquals("第一问", second.messages().get(0).content());
        assertEquals(ChatMessage.Role.ASSISTANT, second.messages().get(1).role());
        assertEquals("第二问", second.messages().get(2).content());
        assertTrue(second.tools().isEmpty(), "工单 01 骨架无工具清单");
    }

    @Test
    void registeredToolsAppearInRequestToolSpecs() throws IOException {
        Session session = newSession();
        // 真 ToolsService + 注册一个工具：请求的 tools 清单应携带它
        dev.duo.harness.core.api.Context toolsRoot = dev.duo.harness.core.api.Context.root();
        toolsRoot.plugin(new dev.duo.harness.tools.ToolsPlugin(), null).awaitStartup();
        dev.duo.harness.tools.ToolsService impl = toolsRoot.as(ToolsView.class).tools();
        impl.register(toolsRoot, echoDef());
        List<ChatRequest> captured = new ArrayList<>();
        LlmAdapter capturing = new LlmAdapter() {
            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                captured.add(request);
                return new LlmTurn("ok", List.of());
            }

            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                throw new UnsupportedOperationException("循环路径走 streamTurn");
            }
        };
        ToolCallingAgent agent = new ToolCallingAgent(capturing, impl, session, "你是助手", 10);

        agent.send("问", AgentListener.NONE);

        assertEquals(1, captured.size());
        assertEquals(1, captured.get(0).tools().size(), "注册的工具应出现在请求清单");
        assertEquals("echo", captured.get(0).tools().get(0).name());
    }

    private static dev.duo.harness.tools.ToolDefinition echoDef() {
        return new dev.duo.harness.tools.ToolDefinition() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "回声工具";
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode parameters() {
                return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("type", "object");
            }

            @Override
            public String execute(dev.duo.harness.tools.ToolExecution execution) {
                return "echo:ok";
            }
        };
    }

    @Test
    void reasoningFromToolRoundIsPassedBackOnNextRequest() throws IOException {
        Session session = newSession();
        dev.duo.harness.core.api.Context toolsRoot = dev.duo.harness.core.api.Context.root();
        toolsRoot.plugin(new dev.duo.harness.tools.ToolsPlugin(), null).awaitStartup();
        dev.duo.harness.tools.ToolsService impl = toolsRoot.as(ToolsView.class).tools();
        impl.register(toolsRoot, echoDef());
        List<ChatRequest> captured = new ArrayList<>();
        // 第 1 轮返回工具调用 + 思考内容，第 2 轮给出最终回答
        LlmAdapter thinkingAdapter = new LlmAdapter() {
            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                captured.add(request);
                if (captured.size() == 1) {
                    textSink.accept("");
                    return new LlmTurn("", List.of(
                            new dev.duo.harness.llm.ToolCallRequest("call_1", "echo", "{}")),
                            "需要回声一下");
                }
                textSink.accept("done");
                return new LlmTurn("done", List.of());
            }

            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                throw new UnsupportedOperationException("循环路径走 streamTurn");
            }
        };
        ToolCallingAgent agent = new ToolCallingAgent(thinkingAdapter, impl, session, "你是助手", 10);

        AgentReply reply = agent.send("问", AgentListener.NONE);

        assertEquals("done", reply.finalText());
        assertTrue(reply.completed());
        // 第 1 轮请求：仅含 user 消息（尚无 assistant 工具调用，无思考内容可回传）
        assertEquals(1, captured.get(0).messages().size());
        assertEquals(ChatMessage.Role.USER, captured.get(0).messages().get(0).role());
        // 第 2 轮请求：上一轮的思考内容必须出现在 assistant(tool_calls) 消息上
        ChatMessage withCalls = captured.get(1).messages().stream()
                .filter(m -> m.role() == ChatMessage.Role.ASSISTANT && m.toolCalls() != null)
                .findFirst().orElseThrow();
        assertEquals("需要回声一下", withCalls.reasoningContent(),
                "思考模式 provider 要求工具调用轮的思考内容原样传回");
        assertEquals("echo", withCalls.toolCalls().get(0).name());
    }

    @Test
    void longChainKeepsLastNonEmptyReasoningWhenModelOmitsIt() throws IOException {
        // BUG-20260913-03 复现形态：实测 5+ 轮链中模型某轮省略 reasoning 输出，
        // 旧实现无条件清空 pendingReasoning → 下轮请求缺字段 400。
        // 修复语义：链内保留最近一次非空思考，后续每轮请求都携带。
        Session session = newSession();
        dev.duo.harness.core.api.Context toolsRoot = dev.duo.harness.core.api.Context.root();
        toolsRoot.plugin(new dev.duo.harness.tools.ToolsPlugin(), null).awaitStartup();
        dev.duo.harness.tools.ToolsService impl = toolsRoot.as(ToolsView.class).tools();
        impl.register(toolsRoot, echoDef());
        List<ChatRequest> captured = new ArrayList<>();
        LlmAdapter flakyThinking = new LlmAdapter() {
            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                captured.add(request);
                String reasoning = captured.size() == 1 ? "链首轮思考" : null;
                return new LlmTurn("", List.of(new dev.duo.harness.llm.ToolCallRequest(
                        "call_" + captured.size(), "echo", "{}")), reasoning);
            }

            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                throw new UnsupportedOperationException("循环路径走 streamTurn");
            }
        };
        ToolCallingAgent agent = new ToolCallingAgent(flakyThinking, impl, session, "你是助手", 6);

        AgentReply reply = agent.send("长链任务", AgentListener.NONE);

        assertFalse(reply.completed(), "每轮都要求调工具，应跑满 6 轮上限");
        assertEquals(6, captured.size(), "6 轮共 6 次请求");
        for (int i = 1; i < captured.size(); i++) {
            ChatMessage lastWithCalls = captured.get(i).messages().stream()
                    .filter(m -> m.role() == ChatMessage.Role.ASSISTANT && m.toolCalls() != null)
                    .reduce((first, second) -> second).orElseThrow();
            assertEquals("链首轮思考", lastWithCalls.reasoningContent(),
                    "第 " + (i + 1) + " 轮请求的最后一条工具调用消息应携带链内最近非空思考");
        }
    }

    @Test
    void invalidConstructorArgsRejected() throws IOException {
        Session session = newSession();
        LlmAdapter adapter = scriptedAdapter("ok");

        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> new ToolCallingAgent(adapter, noTools(), session, (PromptRegistry) null, 10),
                "prompt 注册表缺位应被拒绝");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ToolCallingAgent(adapter, noTools(), session, "你是助手", 0),
                "迭代上限至少为 1");
    }

    @Test
    void promptRegistryComposesIntoRequestAndSupportsScopeRemoval() throws Exception {
        Session session = newSession();
        dev.duo.harness.agent.PromptRegistry prompts =
                new dev.duo.harness.agent.PromptRegistry("你是谨慎的助手");
        List<ChatRequest> captured = new ArrayList<>();
        LlmAdapter capturing = new StreamOnlyAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                captured.add(request);
                onChunk.accept(new ChatChunk("ok"));
            }
        };
        dev.duo.harness.core.api.Context owner = dev.duo.harness.core.api.Context.root();
        PromptRegistry localView = prompts;
        dev.duo.harness.core.api.Disposable removal =
                localView.register(owner, new dev.duo.harness.agent.PromptFragment("safety", "不做危险操作"));

        ToolCallingAgent agent = new ToolCallingAgent(capturing, noTools(), session, prompts, 10);
        agent.send("问", AgentListener.NONE);
        removal.dispose();
        agent.send("再问", AgentListener.NONE);
        owner.dispose();

        // 组装顺序：用户指令最前 + 片段按注册序；摘除后片段消失
        assertTrue(captured.get(0).systemPrompt().startsWith("你是谨慎的助手"),
                captured.get(0).systemPrompt());
        assertTrue(captured.get(0).systemPrompt().contains("不做危险操作"),
                captured.get(0).systemPrompt());
        assertFalse(captured.get(1).systemPrompt().contains("不做危险操作"),
                "注销后片段不再参与组装: " + captured.get(1).systemPrompt());
    }

    @Test
    void emptyRegistryFallsBackToDefaultSystemPrompt() throws IOException {
        Session session = newSession();
        List<ChatRequest> captured = new ArrayList<>();
        LlmAdapter capturing = new StreamOnlyAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                captured.add(request);
                onChunk.accept(new ChatChunk("ok"));
            }
        };
        ToolCallingAgent agent = new ToolCallingAgent(capturing, noTools(), session,
                new dev.duo.harness.agent.PromptRegistry(null), 10);

        agent.send("问", AgentListener.NONE);

        assertEquals(dev.duo.harness.llm.LlmConfig.DEFAULT_SYSTEM_PROMPT, captured.get(0).systemPrompt(),
                "注册表与用户配置全空时落内置缺省");
    }
}
