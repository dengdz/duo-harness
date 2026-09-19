package dev.duo.harness.tools.internal;

import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.tools.Answerer;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;
import dev.duo.harness.tools.InteractionService;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 交互服务实现：回答者注册表（注册序）+ 询问遍历。
 *
 * <p>注册即注册方作用域的 effect（与工具注册 / guard 同模式，插件停止自动摘除）；
 * 询问按注册序遍历，首个给出回答者胜出，全部放弃或无人在场时 fail-closed。</p>
 */
public final class InteractionRegistry implements InteractionService {

    /** 回答者链（注册序；CopyOnWrite 支撑遍历时并发摘除）。 */
    private final List<Answerer> answerers = new CopyOnWriteArrayList<>();

    @Override
    public Disposable register(dev.duo.harness.core.api.Context registrant, Answerer answerer) {
        Objects.requireNonNull(registrant, "registrant");
        Objects.requireNonNull(answerer, "answerer");
        // 与 ToolsServiceImpl 同模式：先挂注册方生命周期（作用域已销毁时此处抛出），
        // 再入列——注册失败不留残留条目
        Disposable removal = registrant.effect(() -> answerers.remove(answerer));
        answerers.add(answerer);
        return removal;
    }

    /**
     * 询问一次：请求携发起呈现位标记时**发起方回答者优先**（M19 亲和路由，
     * ADR-0020 决策 7——"谁发起谁作答"）：先问同标记的回答者，其放弃（null）
     * 或缺席才轮注册序全遍历；注册序兜底保留，单呈现位部署零感。无标记的请求
     * 直接注册序。全部放弃或无人在场时 fail-closed。
     */
    @Override
    public InteractionAnswer ask(InteractionRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.presenterId() != null) {
            for (Answerer answerer : answerers) {
                if (!request.presenterId().equals(answerer.presenterId())) {
                    continue;
                }
                InteractionAnswer answer = answerer.answer(request);
                if (answer != null) {
                    return answer;
                }
                break; // 发起方在场但放弃作答权——交注册序（含其余回答者）
            }
        }
        for (Answerer answerer : answerers) {
            InteractionAnswer answer = answerer.answer(request);
            if (answer != null) {
                return answer;
            }
        }
        return InteractionAnswer.failClosed();
    }
}
