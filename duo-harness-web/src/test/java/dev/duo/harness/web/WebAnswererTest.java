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

    @Test
    void concurrentAsksQueueAndCompleteOldestFirst() throws Exception {
        // 审批小队列（M23 工单 03）：并发到达的第二个 ask 不再 failClosed 拒绝——
        // FIFO 排队，POST 作答完成最旧，逐个放行（架构天然串行下此为防御性升级，
        // 多标签/并发路径不悬空）
        WebAnswerer webAnswerer = new WebAnswerer(10_000);
        AtomicReference<InteractionAnswer> first = new AtomicReference<>();
        AtomicReference<InteractionAnswer> second = new AtomicReference<>();
        CountDownLatch firstArrived = new CountDownLatch(1);
        CountDownLatch secondArrived = new CountDownLatch(1);
        Thread t1 = Thread.ofVirtual().start(() -> {
            firstArrived.countDown();
            first.set(webAnswerer.answer(InteractionRequest.approval("bash", "{}")));
        });
        Thread t2 = Thread.ofVirtual().start(() -> {
            secondArrived.countDown();
            second.set(webAnswerer.answer(InteractionRequest.approval("write", "{}")));
        });
        // 两个 ask 都在队（排队化后 currentPending 恒指最旧）
        assertTrue(firstArrived.await(2, TimeUnit.SECONDS) && secondArrived.await(2, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 3_000;
        while (webAnswerer.pendingCount() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(2, webAnswerer.pendingCount(), "两个请求都在队: ");

        assertTrue(webAnswerer.complete(true, List.of()), "第一次作答完成最旧（第一个）");
        assertTrue(webAnswerer.complete(false, List.of()), "第二次作答完成下一个");
        t1.join(3_000);
        t2.join(3_000);
        assertTrue(first.get().approved(), "第一个拿到 allow");
        assertFalse(second.get().approved(), "第二个拿到 deny");
        assertEquals("web", first.get().source());
        assertEquals(0, webAnswerer.pendingCount(), "队列清空");
    }

    @Test
    void interruptedAskFailsClosedAndQueueClearedForNext() throws Exception {
        // 中断余项合成 deny（M23 工单 03）：等待中的 ask 被打断（暂停传导）→
        // fail-closed 返回 + 队列移除——后续 ask 不受污染，无悬挂请求
        WebAnswerer webAnswerer = new WebAnswerer(60_000);
        AtomicReference<InteractionAnswer> got = new AtomicReference<>();
        Thread asker = Thread.ofVirtual().start(() ->
                got.set(webAnswerer.answer(InteractionRequest.approval("bash", "{}"))));
        long deadline = System.currentTimeMillis() + 3_000;
        while (webAnswerer.pendingCount() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        asker.interrupt(); // 模拟协作式中断打断工具线程
        asker.join(3_000);
        assertFalse(got.get().approved(), "打断按 fail-closed（deny）返回");
        assertEquals(InteractionAnswer.SOURCE_FAIL_CLOSED, got.get().source());
        assertEquals(0, webAnswerer.pendingCount(), "中断后队列无残留");

        // 后续 ask 正常
        AtomicReference<InteractionAnswer> next = new AtomicReference<>();
        Thread nextAsker = Thread.ofVirtual().start(() ->
                next.set(webAnswerer.answer(InteractionRequest.approval("read", "{}"))));
        deadline = System.currentTimeMillis() + 3_000;
        while (webAnswerer.pendingCount() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(webAnswerer.complete(true, List.of()));
        nextAsker.join(3_000);
        assertTrue(next.get().approved(), "中断不污染后续 ask");
    }
}

/** answers 服务的视图接口（方法名即服务名 "answers"）。 */
interface AnswersView4Web {

    InteractionService answers();
}
