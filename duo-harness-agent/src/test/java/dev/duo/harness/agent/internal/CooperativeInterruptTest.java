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
                + "保留+未派发合成）、流式段中断收口、恢复续接、空闲请求拒绝（4 用例） ===");
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
    @Timeout(20)
    void streamPhaseInterruptKeepsFlowingText() throws Exception {
        // 流式段中断：requestInterrupt 打断 streamTurn 的阻塞读（此处以 latch 模拟），
        // 适配器抛出的异常在标志位下收敛为中断收口——已流出文本保留
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            ToolsService tools = root.as(ToolsServiceView.class).tools();
            CountDownLatch streaming = new CountDownLatch(1);
            LlmAdapter llm = new LlmAdapter() {
                @Override public void stream(ChatRequest request,
                        java.util.function.Consumer<ChatChunk> onChunk) {
                    throw new UnsupportedOperationException();
                }
                @Override public LlmTurn streamTurn(ChatRequest request,
                        java.util.function.Consumer<String> textSink) {
                    textSink.accept("流式开头");
                    streaming.countDown();
                    // 模拟真实流式阻塞读：中断打断后以异常冒出（而非正常返回）
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("模拟流被中断打断", e);
                    }
                    throw new IllegalStateException("不应在未中断时到达");
                }
            };
            Session session = Session.create(tempDir.resolve("stream-sessions"));
            ToolCallingAgent agent = new ToolCallingAgent(llm, tools, session,
                    new PromptRegistry("测试"));

            Thread sender = Thread.ofVirtual().start(() ->
                    agent.send("问", AgentListener.NONE));
            assertTrue(streaming.await(10, TimeUnit.SECONDS));
            assertTrue(agent.requestInterrupt());
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
        Context root = Context.root();
        try {
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            ToolsService tools = root.as(ToolsServiceView.class).tools();
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
                        textSink.accept("部分");
                        // 模拟流式阻塞：中断打断后异常冒出（正常返回是未中断路径）
                        try {
                            Thread.sleep(10_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("流被中断打断", e);
                        }
                        return new LlmTurn("不应到达", List.of());
                    }
                    return new LlmTurn("续答", List.of());
                }
            };
            Session session = Session.create(tempDir.resolve("flag-sessions"));
            ToolCallingAgent agent = new ToolCallingAgent(llm, tools, session,
                    new PromptRegistry("测试"));
            java.util.concurrent.atomic.AtomicReference<dev.duo.harness.agent.AgentReply> reply =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread sender = Thread.ofVirtual().start(() -> reply.set(agent.send("问", AgentListener.NONE)));
            assertTrue(turnStarted.await(10, TimeUnit.SECONDS));
            assertTrue(agent.requestInterrupt());
            sender.join(10_000);
            assertFalse(reply.get().completed());
            assertTrue(reply.get().interrupted(), "reply 携带中断标志");
            session.close();
        } finally {
            root.dispose();
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
