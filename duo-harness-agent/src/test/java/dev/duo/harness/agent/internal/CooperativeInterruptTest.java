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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协作式中断用例（M23 工单 02，ADR-0025 决策一）：requestInterrupt 置标志 + 打断
 * send 线程——工具段（bash waitFor InterruptedException→杀树同构）与流式段收敛为
 * 中断收口：已流出文本落 assistant/interrupted、本轮未派发调用补合成结果、
 * reply.interrupted()=true；会话停在可恢复态，下一次 send 即续接。
 */
class CooperativeInterruptTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：CooperativeInterruptTest —— 协作式中断：工具段中断收口（流出文本"
                + "保留+未派发合成）、流式段中断收口（阻塞读抛出/不可打断读回调收敛）、恢复续接、"
                + "空闲请求拒绝（5 用例） ===");
    }

    @TempDir
    Path tempDir;

    /** 阻塞探针工具：执行等待放行闩，模拟飞行中的长工具（可被线程打断唤醒）。 */
    private static ToolDefinition blockingProbe(CountDownLatch release) {
        return new ToolDefinition() {
            @Override public String name() { return "probe"; }
            @Override public String description() { return "阻塞探针"; }
            @Override public com.fasterxml.jackson.databind.JsonNode parameters() {
                return JsonNodeFactory.instance.objectNode().put("type", "object");
            }
            @Override public Object execute(ToolExecution execution) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("probe 被中断", e);
                }
                return "probe-done";
            }
        };
    }

    @Test
    @Timeout(20)
    void toolPhaseInterruptCollectsFlowAndSynthesizesRest() throws Exception {
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            ToolsService tools = root.as(ToolsServiceView.class).tools();
            CountDownLatch probeRelease = new CountDownLatch(1);
            tools.register(root, blockingProbe(probeRelease));

            // 首轮：先流出一段文本，再发起三个工具调用（第一个阻塞探针 + 两个未派发）
            CountDownLatch turnStarted = new CountDownLatch(1);
            AtomicInteger turn = new AtomicInteger();
            LlmAdapter llm = new LlmAdapter() {
                @Override public void stream(ChatRequest request,
                        java.util.function.Consumer<ChatChunk> onChunk) {
                    throw new UnsupportedOperationException();
                }
                @Override public LlmTurn streamTurn(ChatRequest request,
                        java.util.function.Consumer<String> textSink) {
                    if (turn.incrementAndGet() == 1) {
                        turnStarted.countDown();
                        textSink.accept("已经流出的一部分回答");
                        return new LlmTurn("", List.of(
                                new ToolCallRequest("c1", "probe", "{}"),
                                new ToolCallRequest("c2", "probe", "{}"),
                                new ToolCallRequest("c3", "probe", "{}")));
                    }
                    textSink.accept("续接后的回答");
                    return new LlmTurn("续接后的回答", List.of());
                }
            };

            Session session = Session.create(tempDir.resolve("sessions"));
            ToolCallingAgent agent = new ToolCallingAgent(llm, tools, session,
                    new PromptRegistry("测试"), 5, 5, null, ChatAgent.PRESENTER_CLI);

            Thread sender = Thread.ofVirtual().start(() ->
                    agent.send("原始问题", AgentListener.NONE));
            assertTrue(turnStarted.await(10, TimeUnit.SECONDS), "首轮应已开始");
            // 等工具真正在飞（探针阻塞中）再请求中断——中断打断探针 await
            Thread.sleep(200);
            assertTrue(agent.requestInterrupt(), "中断请求被接受");
            probeRelease.countDown(); // 双保险：探针即使不被打断也能被放行收尾
            sender.join(10_000);

            // 事件时序：user → [已流出文本的 interrupted 标记] → 第一个工具对（探针执行
            // 被打断转 error 结果）→ 未派发的 c2/c3 补合成对。标记在工具对之后（工具段
            // 收敛回循环顶才收口）——按事件集合断言顺序关键点
            List<SessionEvent> events = session.events();
            List<String> types = events.stream().map(SessionEvent::type).toList();
            assertTrue(types.indexOf(SessionEvent.ASSISTANT_INTERRUPTED) > 0, "中断标记落日志: " + types);
            SessionEvent mark = events.stream()
                    .filter(e -> SessionEvent.ASSISTANT_INTERRUPTED.equals(e.type())).findFirst().orElseThrow();
            assertEquals("已经流出的一部分回答", mark.text(), "已流出文本完整保留");
            // 三个调用全部成对（无悬空 tool/call）——第一个真实执行（被中断转错误），
            // 后两个合成（未派发）
            long calls = types.stream().filter(SessionEvent.TOOL_CALL::equals).count();
            long results = types.stream().filter(SessionEvent.TOOL_RESULT::equals).count();
            assertEquals(3, calls, "三个调用都落 tool/call: " + types);
            assertEquals(3, results, "成对无悬空: " + types);
            assertTrue(events.stream().filter(e -> SessionEvent.TOOL_RESULT.equals(e.type()))
                    .skip(1).allMatch(e -> e.text().contains("[interrupted]")),
                    "未派发调用的结果为合成说明");

            // 中断后的续接：新 send 正常完成（会话停在可恢复态）
            dev.duo.harness.agent.AgentReply resumed = agent.send("继续", AgentListener.NONE);
            assertTrue(resumed.completed());
            assertEquals("续接后的回答", resumed.finalText());
            session.close();
        } finally {
            root.dispose();
        }
    }

    @Test
    @Timeout(15)
    void streamPhaseInterruptKeepsFlowingText() throws Exception {
        // 流式段中断（inLlmStream 门下的契约）：流式读不可打断，收敛点在 chunk 回调——
        // 已流出文本保留落 assistant/interrupted，页面据此呈现中断标记
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            ToolsService tools = root.as(ToolsServiceView.class).tools();
            CountDownLatch firstChunk = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1);
            LlmAdapter llm = new LlmAdapter() {
                @Override public void stream(ChatRequest request,
                        java.util.function.Consumer<ChatChunk> onChunk) {
                    throw new UnsupportedOperationException();
                }
                @Override public LlmTurn streamTurn(ChatRequest request,
                        java.util.function.Consumer<String> textSink) {
                    textSink.accept("流式开头");
                    firstChunk.countDown();
                    try {
                        gate.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    textSink.accept("后续块");
                    throw new AssertionError("不应到达：置位后的 chunk 回调应抛出收敛");
                }
            };
            Session session = Session.create(tempDir.resolve("stream-sessions"));
            ToolCallingAgent agent = new ToolCallingAgent(llm, tools, session,
                    new PromptRegistry("测试"));

            Thread sender = Thread.ofVirtual().start(() ->
                    agent.send("问", AgentListener.NONE));
            assertTrue(firstChunk.await(10, TimeUnit.SECONDS));
            assertTrue(agent.requestInterrupt());
            gate.countDown();
            sender.join(10_000);

            SessionEvent mark = session.events().stream()
                    .filter(e -> SessionEvent.ASSISTANT_INTERRUPTED.equals(e.type()))
                    .findFirst().orElseThrow();
            assertEquals("流式开头", mark.text(), "流式段中断同样保留已流出文本");
            session.close();
        } finally {
            root.dispose();
        }
    }

    @Test
    @Timeout(15)
    void idleInterruptRequestIsRejected() {
        // 空闲（无 send 在飞）时 requestInterrupt 无的放矢——返回 false 由调用方提示
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            ToolsService tools = root.as(ToolsServiceView.class).tools();
            Session session = Session.create(tempDir.resolve("idle-sessions"));
            ToolCallingAgent agent = new ToolCallingAgent(fixedReply("答"), tools, session,
                    new PromptRegistry("测试"));
            assertFalse(agent.requestInterrupt(), "空闲时中断请求应被拒绝");
            session.close();
        } finally {
            root.dispose();
        }
    }

    @Test
    @Timeout(15)
    void interruptedReplyCarriesFlagAndCompletesFalse() throws Exception {
        // reply 语义：interrupted=true、completed=false、finalText 为中断说明
        // （流式段回调收敛模型：读不可打断，置位后的 chunk 抛出收口）
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            ToolsService tools = root.as(ToolsServiceView.class).tools();
            CountDownLatch firstChunk = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1);
            LlmAdapter llm = new LlmAdapter() {
                @Override public void stream(ChatRequest request,
                        java.util.function.Consumer<ChatChunk> onChunk) {
                    throw new UnsupportedOperationException();
                }
                @Override public LlmTurn streamTurn(ChatRequest request,
                        java.util.function.Consumer<String> textSink) {
                    textSink.accept("部分");
                    firstChunk.countDown();
                    try {
                        gate.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    textSink.accept("后续块");
                    throw new AssertionError("不应到达：置位后的 chunk 回调应抛出收敛");
                }
            };
            Session session = Session.create(tempDir.resolve("flag-sessions"));
            ToolCallingAgent agent = new ToolCallingAgent(llm, tools, session,
                    new PromptRegistry("测试"));
            java.util.concurrent.atomic.AtomicReference<dev.duo.harness.agent.AgentReply> reply =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread sender = Thread.ofVirtual().start(() -> reply.set(agent.send("问", AgentListener.NONE)));
            assertTrue(firstChunk.await(10, TimeUnit.SECONDS));
            assertTrue(agent.requestInterrupt());
            gate.countDown();
            sender.join(10_000);
            assertFalse(reply.get().completed());
            assertTrue(reply.get().interrupted(), "reply 携带中断标志");
            session.close();
        } finally {
            root.dispose();
        }
    }

    @Test
    @Timeout(15)
    void streamingInterruptConvergesAtNextChunkWithoutInterruptibleRead() throws Exception {
        // 验收实测修正：真实适配器阻塞在 socket 流的 readLine 上，线程 interrupt 打不断
        // ——中断后 chunk 照常到达（本用例以吞中断的阻塞模拟该语义），收敛点在 chunk
        // 回调：置位后的下一个 chunk 即抛出收口，已流出文本保留、后续块不落日志。
        // 修正二（inLlmStream 门）：流式段 requestInterrupt 不打断线程——打断击不醒读，
        // 却可能击中正在落盘的 lockChannel（InterruptibleChannel 关闭连坐独占锁）；
        // 断言线程从未被打断 + 收敛后通道健康（续接 send 正常完成）
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            ToolsService tools = root.as(ToolsServiceView.class).tools();
            CountDownLatch firstChunk = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1);
            AtomicBoolean threadInterrupted = new AtomicBoolean(false);
            AtomicInteger turns = new AtomicInteger();
            LlmAdapter llm = new LlmAdapter() {
                @Override public void stream(ChatRequest request,
                        java.util.function.Consumer<ChatChunk> onChunk) {
                    throw new UnsupportedOperationException();
                }
                @Override public LlmTurn streamTurn(ChatRequest request,
                        java.util.function.Consumer<String> textSink) {
                    if (turns.incrementAndGet() > 1) {
                        return new LlmTurn("续答", List.of()); // 中断后的续接：正常直答
                    }
                    textSink.accept("中断前的块");
                    firstChunk.countDown();
                    blockLikeRealReadLine(gate, threadInterrupted);
                    textSink.accept("中断后仍到达的块");
                    throw new AssertionError("不应到达：置位后的 chunk 回调应抛出收敛");
                }
            };
            Session session = Session.create(tempDir.resolve("chunk-sessions"));
            ToolCallingAgent agent = new ToolCallingAgent(llm, tools, session,
                    new PromptRegistry("测试"));
            java.util.concurrent.atomic.AtomicReference<dev.duo.harness.agent.AgentReply> reply =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread sender = Thread.ofVirtual().start(() -> reply.set(agent.send("问", AgentListener.NONE)));
            assertTrue(firstChunk.await(10, TimeUnit.SECONDS));
            assertTrue(agent.requestInterrupt());
            gate.countDown(); // 后续 chunk 照常到达（读不可打断的真实语义）
            sender.join(10_000);
            assertFalse(sender.isAlive(), "send 应已收敛");
            assertFalse(threadInterrupted.get(), "流式段中断不得打断 send 线程（inLlmStream 门）");
            assertTrue(reply.get().interrupted(), "流式段中断按回调收敛");
            SessionEvent mark = session.events().stream()
                    .filter(e -> SessionEvent.ASSISTANT_INTERRUPTED.equals(e.type()))
                    .findFirst().orElseThrow();
            assertEquals("中断前的块", mark.text(), "中断后的 chunk 不落日志");
            assertFalse(session.events().stream().anyMatch(e -> e.text() != null
                    && e.text().contains("中断后仍到达的块")), "置位后的块整体不落日志");
            // 通道健康：中断标记落盘成功后，续接 send 正常完成（带伤通道会 ClosedChannelException）
            dev.duo.harness.agent.AgentReply resumed = agent.send("继续", AgentListener.NONE);
            assertTrue(resumed.completed(), "中断后续接正常（落盘通道无伤）");
            session.close();
        } finally {
            root.dispose();
        }
    }

    /** 模拟 socket 流上的 readLine：对线程中断无响应（只记录被打断的事实供断言），
     * 只有数据到达/流关闭才返回。 */
    private static void blockLikeRealReadLine(CountDownLatch gate, AtomicBoolean threadInterrupted) {
        while (true) {
            try {
                gate.await(10, TimeUnit.SECONDS);
                return;
            } catch (InterruptedException e) {
                // 真实 readLine 对线程中断免疫：记录事实、清标志继续阻塞（防 await 立即重抛）
                threadInterrupted.set(true);
                Thread.interrupted();
            }
        }
    }

    /** 固定直答 mock LLM。 */
    private static LlmAdapter fixedReply(String reply) {
        return new LlmAdapter() {
            @Override public void stream(ChatRequest request,
                    java.util.function.Consumer<ChatChunk> onChunk) {
                onChunk.accept(new ChatChunk(reply));
            }
            @Override public LlmTurn streamTurn(ChatRequest request,
                    java.util.function.Consumer<String> textSink) {
                textSink.accept(reply);
                return new LlmTurn(reply, List.of());
            }
        };
    }

    interface ToolsServiceView {
        ToolsService tools();
    }
}
