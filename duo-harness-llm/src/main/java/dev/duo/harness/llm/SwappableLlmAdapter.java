package dev.duo.harness.llm;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 可换执行链的装饰适配器（M24 工单 09，ADR-0026 决策六）：agent 与治理持有本
 * 装饰器，{@link #swap} 原子替换内部委托——/model 切换「下一 turn 生效」的
 * 执行绑定机制。装饰器同时传给两者，保证治理计量与对话调用始终同一执行链。
 *
 * <p>线程约定：{@code swap} 为 volatile 引用写（调用方串行——斜杠命令单线程）；
 * 读方（agent 工具循环虚拟线程）免锁，切换瞬间的在飞调用走完旧链（旧链对象
 * 由其持有，无资源回收问题）。</p>
 */
public final class SwappableLlmAdapter implements LlmAdapter {

    private volatile LlmAdapter delegate;

    public SwappableLlmAdapter(LlmAdapter initial) {
        this.delegate = Objects.requireNonNull(initial, "initial");
    }

    /** 原子替换内部执行链（/model 切换的执行绑定入口）。 */
    public void swap(LlmAdapter next) {
        this.delegate = Objects.requireNonNull(next, "next");
    }

    @Override
    public void stream(ChatRequest request, Consumer<ChatChunk> onChunk) {
        delegate.stream(request, onChunk);
    }

    @Override
    public LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink) {
        return delegate.streamTurn(request, textSink);
    }
}
