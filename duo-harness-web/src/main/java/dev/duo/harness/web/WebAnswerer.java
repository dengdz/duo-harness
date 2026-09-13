package dev.duo.harness.web;

import dev.duo.harness.tools.Answerer;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HITL Web answerer（M8，ADR-0008 的 Web 呈现位）：实现 M6 {@link Answerer} 接口
 * 注册进交互 seam——待答请求经 SSE 推送为页面交互卡片，用户点选/输入后
 * {@code POST /api/answer} 完成等待中的请求。
 *
 * <p>阻塞语义：{@code answer()} 在虚拟线程上等待 CompletableFuture（工具链本就
 * 运行在虚拟线程，阻塞不占平台线程，ADR-0002 同源）。**断连 fail-closed**：
 * 页面 SSE 断开时 {@link #failClosedAll()} 立即以拒绝完成全部悬空请求——
 * 人不在环 = 不批准（ADR-0008 语义延伸到 Web 呈现位）。重复回答 / 无待答
 * 请求为幂等拒绝。</p>
 */
public final class WebAnswerer implements Answerer {

    /** 回答者来源标识（审计署名）。 */
    public static final String SOURCE = "web";

    /** 待答状态：请求 + 完成器（端点写入答案、断连写入 fail-closed）。 */
    record Pending(InteractionRequest request, CompletableFuture<InteractionAnswer> future) { }

    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private final long answerTimeoutMs;

    /** @param answerTimeoutMs 兜底超时（超时按 fail-closed；正常流程由断连触发） */
    public WebAnswerer(long answerTimeoutMs) {
        this.answerTimeoutMs = answerTimeoutMs;
    }

    @Override
    public InteractionAnswer answer(InteractionRequest request) {
        CompletableFuture<InteractionAnswer> future = new CompletableFuture<>();
        if (!pending.compareAndSet(null, new Pending(request, future))) {
            // 已有悬空请求未决：拒绝新的（串行会话下不应发生，防御性 fail-closed）
            return InteractionAnswer.failClosed();
        }
        try {
            return future.get(answerTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.compareAndSet(new Pending(request, future), null);
            return InteractionAnswer.failClosed();
        } catch (TimeoutException e) {
            pending.compareAndSet(new Pending(request, future), null);
            return InteractionAnswer.failClosed();
        } catch (java.util.concurrent.ExecutionException e) {
            pending.compareAndSet(new Pending(request, future), null);
            return InteractionAnswer.failClosed();
        }
    }

    /**
     * 用户作答（POST /api/answer 调用）：完成当前悬空请求。
     *
     * @param approved 审批语义（提问恒 true）
     * @param values   回答值（提问的选项/自由文本；审批为空）
     * @return true = 完成成功；false = 无待答请求（幂等拒绝）
     */
    boolean complete(boolean approved, List<String> values) {
        Pending waiting = pending.getAndSet(null);
        if (waiting == null) {
            return false;
        }
        return waiting.future().complete(new InteractionAnswer(approved, values, SOURCE));
    }

    /**
     * 断连 fail-closed：SSE 断开时调用——全部悬空请求立即按拒绝完成。
     */
    public void failClosedAll() {
        Pending waiting = pending.getAndSet(null);
        if (waiting != null) {
            waiting.future().complete(InteractionAnswer.failClosed());
        }
    }

    /** 当前待答请求（SSE 推送用；无待答为 null）。 */
    Pending currentPending() {
        return pending.get();
    }
}
