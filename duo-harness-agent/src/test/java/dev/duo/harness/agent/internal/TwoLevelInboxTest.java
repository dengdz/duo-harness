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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两级收件箱用例（M23 工单 01，ADR-0025 决策一）：next-step 级在 step 边界排干
 * （steer，SteerInjectionTest 已覆盖，此处不重复）；next-turn 级**不进当前 turn**
 * ——执行中注入不落日志、不进后续请求，turn 收口后由呈现位经 drainNextTurn 取走
 * （生效 = 开新轮，CLI 接线见 CliPluginTest）。空闲期注入同样只入队，不自动触发。
 */
class TwoLevelInboxTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：TwoLevelInboxTest —— 两级收件箱：next-turn 执行中注入不进当前 turn、"
                + "收口排干先进先出、空闲注入只入队、空白文本两级同拒（3 用例） ===");
    }

    @TempDir
    Path tempDir;

    /** 并发安全只读探针工具（给 turn1 一个工具调用，制造迭代边界）。 */
    private static ToolDefinition probeTool(CountDownLatch executed) {
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
                executed.countDown();
                return "probe-done";
            }
        };
    }

    @Test
    @Timeout(15)
    void nextTurnInjectionStaysOutOfCurrentTurnAndDrainsAtClose() throws Exception {
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            ToolsService tools = root.as(ToolsServiceView.class).tools();
            CountDownLatch toolExecuted = new CountDownLatch(1);
            tools.register(root, probeTool(toolExecuted));

            // 慢 mock LLM：首轮等待注入窗口后调工具（制造迭代边界）；次轮捕获请求并断言
            // next-turn 注入不可见，随后直答
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
                    assertTrue(agent.injectNextTurn("排队消息"), "busy 期间 next-turn 注入被接收");
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

            // 当前 turn 全程不可见：事件日志无排队消息、第二轮请求不含它
            List<String> timeline = session.events().stream()
                    .map(e -> e.type() + ":" + e.text()).toList();
            assertTrue(timeline.stream().noneMatch(t -> t.contains("排队消息")),
                    "next-turn 注入不进当前 turn 的事件日志: " + timeline);
            List<String> contents = secondRequest.get().messages().stream()
                    .map(dev.duo.harness.llm.ChatMessage::content).toList();
            assertTrue(contents.stream().noneMatch(c -> c.contains("排队消息")),
                    "next-turn 注入不进当前 turn 的请求: " + contents);

            // turn 收口（send 返回）后呈现位排干：先进先出，二次排干为空
            assertEquals(List.of("排队消息"), agent.drainNextTurn(), "收口排干返回全部排队文本");
            assertTrue(agent.drainNextTurn().isEmpty(), "二次排干为空");
            assertTrue(toolExecuted.await(0, TimeUnit.SECONDS), "飞行中工具组已完整跑完");
            session.close();
        } finally {
            root.dispose();
        }
    }

    @Test
    @Timeout(15)
    void idleNextTurnInjectionQueuesWithoutAutoStart() {
        // 空闲期 next-turn 注入只入队：不落日志、不自动开轮；send 不消费它，
        // 收口排干时才由呈现位取走
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            ToolsService tools = root.as(ToolsServiceView.class).tools();

            Session session = Session.create(tempDir.resolve("idle-sessions"));
            ToolCallingAgent agent = new ToolCallingAgent(fixedReply("答"), tools, session,
                    new PromptRegistry("测试"));

            assertTrue(agent.injectNextTurn("先排"), "空闲期注入入队");
            dev.duo.harness.agent.AgentReply reply = agent.send("问题", AgentListener.NONE);
            assertTrue(reply.completed());

            assertTrue(session.events().stream().noneMatch(e ->
                            "user/message".equals(e.type()) && "先排".equals(e.text())),
                    "空闲注入不落事件日志（生效交给呈现位收口消费）");
            assertEquals(List.of("先排"), agent.drainNextTurn(), "收口排干取出");
            session.close();
        } finally {
            root.dispose();
        }
    }

    @Test
    void blankTextRejectedOnBothLevels() {
        // 空白/ null 文本两级同拒（与 injectUserMessage 既有纪律一致）
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            ToolsService tools = root.as(ToolsServiceView.class).tools();
            Session session = Session.create(tempDir.resolve("blank-sessions"));
            ToolCallingAgent agent = new ToolCallingAgent(fixedReply("答"), tools, session,
                    new PromptRegistry("测试"));

            assertFalse(agent.injectUserMessage(null));
            assertFalse(agent.injectUserMessage("   "));
            assertFalse(agent.injectNextTurn(null));
            assertFalse(agent.injectNextTurn(" "));
            assertTrue(agent.drainNextTurn().isEmpty(), "拒绝的文本不入队");
            session.close();
        } finally {
            root.dispose();
        }
    }

    /** 固定直答 mock LLM。 */
    private static LlmAdapter fixedReply(String reply) {
        return new LlmAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                onChunk.accept(new ChatChunk(reply));
            }

            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                textSink.accept(reply);
                return new LlmTurn(reply, List.of());
            }
        };
    }

    interface ToolsServiceView {

        ToolsService tools();
    }
}
