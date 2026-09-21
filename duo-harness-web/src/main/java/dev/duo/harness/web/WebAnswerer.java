package dev.duo.harness.web;

import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.tools.Answerer;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HITL Web answerer（M8，ADR-0008 的 Web 呈现位；M23 工单 03 升级排队）：实现
 * {@link Answerer} 接口注册进交互 seam——待答请求经 SSE 推送为页面交互卡片，
 * 用户点选/输入后 {@code POST /api/answer} 完成等待中的请求。
 *
 * <p><b>排队语义（M23 工单 03）</b>：并发到达的多个请求 FIFO 排队而非拒绝第二个
 * （会话串行架构下天然一次一个，排队化是防御性升级——多标签/并发路径不悬空）；
 * POST 作答完成<b>最旧</b>一项（串行下即当前唯一）；等待线程被打断（协作式中断
 * 传导）按 fail-closed 返回并移除自身，后续 ask 不受污染。</p>
 *
 * <p>阻塞语义：{@code answer()} 在虚拟线程上等待 CompletableFuture（工具链本就
 * 运行在虚拟线程，阻塞不占平台线程，ADR-0002 同源）。**无人能答即拒**：
 * {@link #failClosedAll()} 以拒绝完成全部悬空请求（人不在环 = 不批准，ADR-0008）；
 * 调用时机由 {@link WebFace} 判定——全部连接离场且宽限期内无新连接才触发，
 * 刷新断旧立新不误杀（ADR-0010）。重复回答 / 无待答请求为幂等拒绝。</p>
 */
public final class WebAnswerer implements Answerer {

    /** 回答者来源标识（审计署名）。 */
    public static final String SOURCE = "web";

    /** 待答状态：请求 + 完成器（端点写入答案、断连写入 fail-closed）。 */
    record Pending(InteractionRequest request, CompletableFuture<InteractionAnswer> future) { }

    /** FIFO 待答队列（ConcurrentLinkedQueue：ask/complete/interrupt 三方并发）。 */
    private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final long answerTimeoutMs;

    /** @param answerTimeoutMs 兜底超时（超时按 fail-closed；正常流程由断连触发） */
    public WebAnswerer(long answerTimeoutMs) {
        this.answerTimeoutMs = answerTimeoutMs;
    }

    /** 亲和路由（M19，ADR-0020 决策 7）：本回答者代表 Web 呈现位。 */
    @Override
    public String presenterId() {
        return ChatAgent.PRESENTER_WEB;
    }

    @Override
    public InteractionAnswer answer(InteractionRequest request) {
        CompletableFuture<InteractionAnswer> future = new CompletableFuture<>();
        Pending waiting = new Pending(request, future);
        queue.add(waiting);
        try {
            return future.get(answerTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 协作式中断传导（M23 工单 03）：余项合成 deny——fail-closed 返回并移除自身；
            // 不重设线程标志：中断语义由 interruptRequested 布尔承载，残留标志会炸
            // 审计桥随后的 decided 事件落盘（ClosedByInterruptException）
            abort(waiting);
            return InteractionAnswer.failClosed();
        } catch (TimeoutException e) {
            abort(waiting);
            return InteractionAnswer.failClosed();
        } catch (java.util.concurrent.ExecutionException e) {
            abort(waiting);
            return InteractionAnswer.failClosed();
        }
    }

    /** 移除自身并以 fail-closed 完成（消费方已走的完成是幂等空操作，防悬挂）。 */
    private void abort(Pending waiting) {
        queue.remove(waiting);
        waiting.future().complete(InteractionAnswer.failClosed());
    }

    /**
     * 用户作答（POST /api/answer 调用）：完成<b>最旧</b>的悬空请求（串行架构下即
     * 当前唯一——排队为防御性语义）。
     *
     * @param approved 审批语义（提问恒 true）
     * @param values   回答值（提问的选项/自由文本；审批为空）
     * @return true = 完成成功；false = 无待答请求（幂等拒绝）
     */
    boolean complete(boolean approved, List<String> values) {
        Pending waiting = queue.poll();
        if (waiting == null) {
            return false;
        }
        return waiting.future().complete(new InteractionAnswer(approved, values, SOURCE));
    }

    /**
     * 断连 fail-closed：SSE 断开时调用——全部悬空请求立即按拒绝完成。
     */
    public void failClosedAll() {
        Pending waiting;
        while ((waiting = queue.poll()) != null) {
            waiting.future().complete(InteractionAnswer.failClosed());
        }
    }

    /** 当前待答请求数（排队呈现与测试观测用）。 */
    int pendingCount() {
        return queue.size();
    }

    /** 当前最旧待答请求（SSE 推送用；无待答为 null）。 */
    Pending currentPending() {
        return queue.peek();
    }
}
