package dev.duo.harness.example.headless;

import dev.duo.harness.tools.Answerer;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;

import java.util.function.Consumer;

/**
 * headless 回答者（M23 工单 07，ADR-0025）：流内禁交互——审批/提问/计划复核请求
 * 一律自动拒绝并经 {@code errorSink} 发显式 error 帧（消费者可见"有交互被拒"），
 * 流程永不静默挂死。拒绝作为工具错误结果回给模型，由模型自行解释收尾
 * （final 帧正常到达）——对齐 DSH「approval 拒绝是正常结果流」。
 */
public final class HeadlessAnswerer implements Answerer {

    /** headless 呈现位标记（亲和路由自洽：本位发起的请求路由回本回答者）。 */
    public static final String PRESENTER_ID = "headless";

    private final Consumer<String> errorSink;

    public HeadlessAnswerer(Consumer<String> errorSink) {
        this.errorSink = errorSink;
    }

    @Override public InteractionAnswer answer(InteractionRequest request) {
        errorSink.accept("headless 流内禁交互：[" + request.kind() + "] " + request.subject()
                + " 已自动拒绝（如需审批/提问请改用交互式呈现位）");
        return InteractionAnswer.deny(PRESENTER_ID);
    }

    @Override public String presenterId() {
        return PRESENTER_ID;
    }
}
