package dev.duo.harness.tools;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;

/**
 * 交互服务：交互 seam 的机制核（ADR-0008）——回答者的注册表与询问入口。
 *
 * <p>审批（ask 策略裁决为"需人作答"）与提问（ask_user 工具执行本体）都经
 * {@link #ask} 交给在场回答者；按注册序遍历，回答者可放弃作答权（返回 null），
 * 全部放弃或无人在场时 fail-closed（一律按拒绝处理）。</p>
 *
 * <p>机制与呈现分离：本服务不关心谁来回答——AgentRepl 注册 console answerer
 * （M6），Web 面注册 web answerer（M8），机制零改动。经视图接口
 * {@link AnswersView} 寻址（服务名 "answers"，harness 保留裸名）。</p>
 */
public interface InteractionService {

    /** 服务名（harness 保留裸名）。 */
    String SERVICE_NAME = "answers";

    /**
     * 注册回答者，随注册方作用域自动摘除（插件停止即不在场）。
     *
     * @param registrant 注册方 Context（其作用域销毁时回答者自动摘除）
     * @param answerer   回答者实现
     * @return 注销器（手动提前摘除用）
     */
    Disposable register(Context registrant, Answerer answerer);

    /**
     * 询问一次：按注册序遍历回答者，首个给出回答者胜出。
     *
     * @param request 交互请求（审批或提问）
     * @return 回答；无人在场或全部放弃时为 fail-closed 拒绝（{@link InteractionAnswer#failClosed()}）
     */
    InteractionAnswer ask(InteractionRequest request);
}
