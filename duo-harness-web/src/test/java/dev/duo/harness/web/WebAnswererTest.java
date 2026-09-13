package dev.duo.harness.web;

import dev.duo.harness.tools.Answerer;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.InteractionPlugin;
import dev.duo.harness.core.api.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HITL Web answerer 用例（交互 seam 装配）：POST 回答解除阻塞（审批两态 + 提问值）、
 * 断连 fail-closed、超时兜底 fail-closed、重复完成幂等拒绝。
 */
class WebAnswererTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebAnswererTest —— HITL Web answerer：POST 回答解除阻塞（审批/提问）、"
                + "断连 fail-closed、超时兜底、重复完成幂等（5 用例） ===");
    }

    private Context root;

    @AfterEach
    void tearDown() {
        if (root != null) {
            root.dispose();
        }
    }

    @Test
    void approvalCompletedByPostAnswer() throws Exception {
        root = Context.root();
        root.plugin(new InteractionPlugin(), null).awaitStartup();
        InteractionService answers = root.as(AnswersView4Web.class).answers();
        WebAnswerer webAnswerer = new WebAnswerer(5_000);
        answers.register(root, webAnswerer);

        // 模拟 agent 线程：阻塞询问（虚拟线程同语义）
        AtomicReference<InteractionAnswer> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            got.set(answers.ask(InteractionRequest.approval("write_file", "{}")));
            done.countDown();
        });
        // 等 answerer 进入等待态
        while (webAnswerer.currentPending() == null) {
            assertTrue(done.getCount() > 0);
            Thread.sleep(20);
        }

        assertTrue(webAnswerer.complete(true, List.of()), "POST /api/answer 完成悬空请求");
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(got.get().approved());
        assertEquals("web", got.get().source());
    }

    @Test
    void questionAnswerCarriesValues() throws Exception {
        root = Context.root();
        root.plugin(new InteractionPlugin(), null).awaitStartup();
        InteractionService answers = root.as(AnswersView4Web.class).answers();
        WebAnswerer webAnswerer = new WebAnswerer(5_000);
        answers.register(root, webAnswerer);

        AtomicReference<InteractionAnswer> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            got.set(answers.ask(InteractionRequest.question("存哪？", List.of("A", "B"), false)));
            done.countDown();
        });
        while (webAnswerer.currentPending() == null) {
            Thread.sleep(20);
        }

        assertTrue(webAnswerer.complete(true, List.of("B")));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("B"), got.get().values(), "提问回答值原样回传");
    }

    @Test
    void disconnectFailsClosedAllPending() throws Exception {
        root = Context.root();
        root.plugin(new InteractionPlugin(), null).awaitStartup();
        InteractionService answers = root.as(AnswersView4Web.class).answers();
        WebAnswerer webAnswerer = new WebAnswerer(60_000);
        answers.register(root, webAnswerer);

        AtomicReference<InteractionAnswer> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            got.set(answers.ask(InteractionRequest.approval("write_file", "{}")));
            done.countDown();
        });
        while (webAnswerer.currentPending() == null) {
            Thread.sleep(20);
        }

        // 页面关闭（SSE 断开）→ fail-closedAll
        webAnswerer.failClosedAll();
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertFalse(got.get().approved(), "断连 → 悬空审批按拒绝处理");
        assertEquals(InteractionAnswer.SOURCE_FAIL_CLOSED, got.get().source());
    }

    @Test
    void timeoutFailsClosed() {
        WebAnswerer webAnswerer = new WebAnswerer(50);
        InteractionAnswer answer = webAnswerer.answer(InteractionRequest.approval("x", "{}"));
        assertFalse(answer.approved(), "无 POST 回答 → 超时兜底 fail-closed");
        assertEquals(InteractionAnswer.SOURCE_FAIL_CLOSED, answer.source());
    }

    @Test
    void completeWithoutPendingIsIdempotentFalse() {
        WebAnswerer webAnswerer = new WebAnswerer(5_000);
        assertFalse(webAnswerer.complete(true, List.of()), "无待答请求 → 幂等拒绝");
    }
}

/** answers 服务的视图接口（方法名即服务名 "answers"）。 */
interface AnswersView4Web {

    InteractionService answers();
}
