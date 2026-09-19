package dev.duo.harness.agent.internal;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.prompt.PromptRegistry;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.session.Message;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import dev.duo.harness.core.api.Context;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 父级 steer 注入用例（M19 工单 04，缝 1，ADR-0020 决策 8）：**真实异步时序**——
 * 慢 mock LLM（latch 阻塞在首轮调用中）+ 另一线程并发注入。排干只发生在迭代边界：
 * 注入落普通 user/message（连续两条）、下一轮请求可见、飞行中的工具组完整跑完。
 * 同步 mock 会让边界排干永不生效，故本套件全部真实并发。
 */
class SteerInjectionTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SteerInjectionTest —— 父级 steer：迭代边界排干（连续 user/message）、"
                + "下一轮请求可见、飞行中工具组不打断、presenterId 随工具执行传导（2 用例） ===");
    }

    @TempDir
    Path tempDir;

    /** 并发安全只读工具（进并行池执行即证明工具组照常跑完）。 */
    private static ToolDefinition probeTool(List<String> executions, CountDownLatch executed) {
        return new ToolDefinition() {
            @Override
            public String name() {
                return "probe";
            }

            @Override
            public String description() {
                return "只读探针";
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode parameters() {
                return JsonNodeFactory.instance.objectNode().put("type", "object");
            }

            @Override
            public boolean isConcurrencySafe(com.fasterxml.jackson.databind.JsonNode args) {
                return true;
            }

            @Override
            public Object execute(ToolExecution execution) {
                executions.add(execution.presenterId());
                executed.countDown();
                return "probe-done";
            }
        };
    }

    @Test
    @Timeout(15)
    void injectedMessagesDrainAtIterationBoundaryAndVisibleInNextRequest() throws Exception {
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            ToolsService tools = root.as(ToolsServiceView.class).tools();
            List<String> presenterSeen = new CopyOnWriteArrayList<>();
            CountDownLatch toolExecuted = new CountDownLatch(1);
            tools.register(root, probeTool(presenterSeen, toolExecuted));

            // 慢 mock LLM：首轮在调用中等待注入窗口；次轮捕获请求后直答
            CountDownLatch firstTurnStarted = new CountDownLatch(1);
            CountDownLatch injected = new CountDownLatch(1);
            AtomicReference<ChatRequest> secondRequest = new AtomicReference<>();
            LlmAdapter slowLlm = new LlmAdapter() {
                int turn = 0;

                @Override
                public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public LlmTurn streamTurn(ChatRequest request,
                                          java.util.function.Consumer<String> textSink) {
                    turn++;
                    if (turn == 1) {
                        firstTurnStarted.countDown();
                        try {
                            assertTrue(injected.await(10, TimeUnit.SECONDS), "注入应发生在首轮调用中");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return new LlmTurn("", List.of(new ToolCallRequest("call_1", "probe", "{}")));
                    }
                    secondRequest.set(request);
                    textSink.accept("最终回答");
                    return new LlmTurn("最终回答", List.of());
                }
            };

            Session session = Session.create(tempDir.resolve("sessions"));
            ToolCallingAgent agent = new ToolCallingAgent(slowLlm, tools, session,
                    new PromptRegistry("测试"), 5, 5, null, ChatAgent.PRESENTER_CLI);

            Thread injector = new Thread(() -> {
                try {
                    assertTrue(firstTurnStarted.await(10, TimeUnit.SECONDS), "首轮应已开始");
                    assertTrue(agent.injectUserMessage("注入一"), "busy 期间注入被接收");
                    assertTrue(agent.injectUserMessage("注入二"), "多条注入照排");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    injected.countDown();
                }
            });
            injector.start();

            dev.duo.harness.agent.AgentReply reply = agent.send("原始问题", AgentListener.NONE);
            injector.join(5_000);

            assertTrue(reply.completed());
            assertEquals("最终回答", reply.finalText());
            assertTrue(toolExecuted.await(0, TimeUnit.SECONDS), "飞行中工具组已完整跑完");
            assertEquals(List.of(ChatAgent.PRESENTER_CLI), presenterSeen,
                    "presenterId 随工具执行传导进管线（亲和路由的数据源）");

            // 事件时序：原始消息 → 工具对（首轮）→ 迭代边界排干的注入（连续 user/message
            // 按注入序）→ 终版回答。注入不打断飞行工具组：工具对在注入事件之前落盘
            List<SessionEvent> events = session.events();
            List<String> timeline = events.stream()
                    .map(e -> e.type() + ":" + e.text()).toList();
            assertEquals(List.of(
                    SessionEvent.USER_MESSAGE + ":原始问题",
                    SessionEvent.TOOL_CALL + ":{}",
                    SessionEvent.TOOL_RESULT + ":probe-done",
                    SessionEvent.USER_MESSAGE + ":注入一",
                    SessionEvent.USER_MESSAGE + ":注入二",
                    SessionEvent.ASSISTANT_MESSAGE + ":最终回答"), timeline,
                    "注入在迭代边界排干为普通 user/message（多条照排），不打断飞行中工具组");

            // 下一轮请求可见：第二轮请求构造（排干之后）包含注入文本
            List<String> contents = secondRequest.get().messages().stream()
                    .map(dev.duo.harness.llm.ChatMessage::content).toList();
            assertTrue(contents.contains("原始问题") && contents.contains("注入一")
                            && contents.contains("注入二"),
                    "注入文本进下一轮请求（模型下一步可见）: " + contents);
            session.close();
        } finally {
            root.dispose();
        }
    }

    @Test
    @Timeout(15)
    void consecutiveUserMessagesProjectAndReplayCompatibly() throws Exception {
        // 连续 user/message 的投影与回放兼容（ADR-0020 决策 8 的验证义务）：
        // 投影按落盘序原样入列（无特判、无合并），重开会话经日志重放结果一致
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            ToolsService tools = root.as(ToolsServiceView.class).tools();
            tools.register(root, probeTool(new CopyOnWriteArrayList<>(), new CountDownLatch(0)));

            Session session = Session.create(tempDir.resolve("sessions"));
            session.append(SessionEvent.userMessage("第一条"));
            session.append(SessionEvent.userMessage("执行中注入"));
            session.append(SessionEvent.userMessage("再次注入"));
            session.append(SessionEvent.assistantMessage("回复"));

            List<Message> messages = session.deriveMessages();
            assertEquals(4, messages.size());
            assertEquals("第一条", messages.get(0).content());
            assertEquals("执行中注入", messages.get(1).content());
            assertEquals("再次注入", messages.get(2).content());
            assertEquals("回复", messages.get(3).content());

            // 回放：重开同一 JSONL，投影一致
            Path jsonl = session.jsonl();
            session.close();
            Session reloaded = Session.load(jsonl);
            assertEquals(4, reloaded.deriveMessages().size(), "重放投影一致");
            reloaded.close();
        } finally {
            root.dispose();
        }
    }

    interface ToolsServiceView {

        ToolsService tools();
    }
}
