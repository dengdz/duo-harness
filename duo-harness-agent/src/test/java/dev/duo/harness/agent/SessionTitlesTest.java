package dev.duo.harness.agent;

import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话标题生成器用例（工单 M13-06，mock adapter 先例）：成功生成、失败降级截断、
 * in-flight/双开去重（同会话至多一次直答）、续接会话不重生成、限时超时降级。
 */
class SessionTitlesTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SessionTitlesTest —— 会话标题：生成/降级/去重/续接不重生成/超时（5 用例） ===");
    }

    /** 计数直答 adapter：固定回复 + 调用计数（用例按需覆写 stream 定制行为）。 */
    private static class ScriptedAdapter implements LlmAdapter {

        final String reply;
        final AtomicInteger calls = new AtomicInteger();

        ScriptedAdapter(String reply) {
            this.reply = reply;
        }

        @Override
        public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
            calls.incrementAndGet();
            onChunk.accept(new ChatChunk(reply));
        }

        @Override
        public dev.duo.harness.llm.LlmTurn streamTurn(ChatRequest request,
                                                      java.util.function.Consumer<String> textSink) {
            calls.incrementAndGet();
            textSink.accept(reply);
            return new dev.duo.harness.llm.LlmTurn(reply, List.of());
        }
    }

    private Session newSession() throws IOException {
        return Session.create(tempDir.resolve("sessions"));
    }

    /** 等待标题落日志：latch 证生成流程已触发（appendTitle 在其后，顺序无保证），再轮询 title 投影。 */
    private static void awaitTitle(Session session, CountDownLatch done) throws InterruptedException {
        assertTrue(done.await(5, TimeUnit.SECONDS), "生成流程应在时限内被触发");
        long deadline = System.currentTimeMillis() + 5_000;
        while (session.title() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(session.title() != null, "标题应在时限内落日志");
    }

    @Test
    void generatesTitleFromFirstUserMessage() throws Exception {
        Session session = newSession();
        CountDownLatch done = new CountDownLatch(1);
        ScriptedAdapter llm = new ScriptedAdapter("订单查询帮助") {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                super.stream(request, onChunk);
                done.countDown();
            }
        };

        SessionTitles.attach(session, llm, 5);
        session.append(SessionEvent.userMessage("帮我查一下订单"));

        awaitTitle(session, done);
        assertEquals("订单查询帮助", session.title(), "LLM 标题落 latest-wins 投影");
        assertEquals(1, llm.calls.get(), "直答恰一次");
        assertEquals(SessionEvent.TITLE, session.events().get(session.events().size() - 1).type(),
                "标题以 title 事件落日志");
        session.close();
    }

    @Test
    void fallsBackToTruncatedFirstMessageOnFailure() throws Exception {
        Session session = newSession();
        CountDownLatch done = new CountDownLatch(1);
        LlmAdapter failing = new ScriptedAdapter("x") {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                calls.incrementAndGet();
                done.countDown(); // 已尝试即放行降级
                throw new IllegalStateException("模拟 LLM 故障");
            }
        };

        SessionTitles.attach(session, failing, 5);
        String longMessage = "这是一个超过二十个字符的长消息用来验证降级截断行为是否符合预期";
        session.append(SessionEvent.userMessage(longMessage));

        awaitTitle(session, done);
        assertEquals(longMessage.substring(0, SessionTitles.MAX_TITLE_CHARS), session.title(),
                "失败降级 = 首条消息前 20 字");
        session.close();
    }

    @Test
    void duplicateAttachDoesNotGenerateTwice() throws Exception {
        Session session = newSession();
        CountDownLatch done = new CountDownLatch(1);
        ScriptedAdapter llm = new ScriptedAdapter("唯一标题") {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                super.stream(request, onChunk);
                done.countDown();
            }
        };

        SessionTitles.attach(session, llm, 5);
        SessionTitles.attach(session, llm, 5); // 双开形态：第二监听被静态去重表拦下
        session.append(SessionEvent.userMessage("第一问"));

        awaitTitle(session, done);
        Thread.sleep(100); // 给可能的重放窗口留时间——若去重失效会出现第二条 title
        assertEquals(1, session.events().stream()
                        .filter(e -> SessionEvent.TITLE.equals(e.type())).count(),
                "title 事件恰一条");
        assertEquals(1, llm.calls.get(), "直答恰一次");
        session.close();
    }

    @Test
    void resumedSessionWithMessagesDoesNotRegenerate() throws Exception {
        Session session = newSession();
        session.append(SessionEvent.userMessage("第一问"));
        session.append(SessionEvent.assistantMessage("第一答"));

        ScriptedAdapter llm = new ScriptedAdapter("不应生成");
        SessionTitles.attach(session, llm, 5);
        session.append(SessionEvent.userMessage("续接后的新消息")); // 下标 > 0：不触发

        Thread.sleep(200);
        assertNull(session.title(), "续接会话不重生成标题");
        assertEquals(0, llm.calls.get(), "零直答调用");
        session.close();
    }

    @Test
    void timeoutFallsBackToTruncation() throws Exception {
        Session session = newSession();
        LlmAdapter slow = new ScriptedAdapter("慢回复") {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                calls.incrementAndGet();
                onChunk.accept(new ChatChunk(reply));
            }
        };

        String message = "超时降级验证消息";
        SessionTitles.attach(session, slow, 1); // 1 秒超时（包级注入，测试不等生产超时）
        session.append(SessionEvent.userMessage(message));

        long deadline = System.currentTimeMillis() + 5_000;
        while (session.title() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(message, session.title(), "超时降级 = 首条消息（本例不足 20 字不截断）");
        session.close();
    }
}
