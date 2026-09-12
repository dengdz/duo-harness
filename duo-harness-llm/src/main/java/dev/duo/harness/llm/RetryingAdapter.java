package dev.duo.harness.llm;

import dev.duo.harness.core.api.PluginException;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * LLM 调用重试装饰器：包住任意 {@link LlmAdapter}，对可重试异常
 * （{@link RetryableLlmException}——网络故障与 429/502/503/504）按指数退避重试。
 *
 * <p>协议与凭证错误（400/401 等）不是 {@link RetryableLlmException}，直通不重试——
 * 配置错误必须立即暴露，重试只会掩盖问题。</p>
 *
 * <p>流式安全语义：重试仅发生在**尚未向消费方发出任何增量**的失败上；一旦有
 * chunk 已交付，重试会导致内容重复，此时原样上抛。已发出的内容按 LLM 契约保留。</p>
 */
public final class RetryingAdapter implements LlmAdapter {

    /** 默认总尝试次数（含首次）。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** 默认首退避毫秒（后续 ×2：1s → 2s → 4s）。 */
    public static final long DEFAULT_INITIAL_BACKOFF_MS = 1000;

    private final LlmAdapter delegate;
    private final int maxAttempts;
    private final long initialBackoffMs;

    /** @param delegate 被装饰的适配器（默认 3 次尝试、1s 起步退避） */
    public RetryingAdapter(LlmAdapter delegate) {
        this(delegate, DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_BACKOFF_MS);
    }

    /**
     * @param delegate          被装饰的适配器
     * @param maxAttempts       总尝试次数（含首次；至少 1）
     * @param initialBackoffMs  首次重试前等待毫秒（后续 ×2；测试可传 0）
     */
    public RetryingAdapter(LlmAdapter delegate, int maxAttempts, long initialBackoffMs) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 至少为 1: " + maxAttempts);
        }
        if (initialBackoffMs < 0) {
            throw new IllegalArgumentException("initialBackoffMs 不能为负: " + initialBackoffMs);
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
    }

    @Override
    public void stream(ChatRequest request, Consumer<ChatChunk> onChunk) {
        int[] emitted = {0};
        Consumer<ChatChunk> counting = chunk -> {
            emitted[0]++;
            onChunk.accept(chunk);
        };
        for (int attempt = 1; ; attempt++) {
            try {
                delegate.stream(request, counting);
                return;
            } catch (RetryableLlmException e) {
                if (emitted[0] > 0) {
                    // 已有增量交付：重试会导致内容重复，原样上抛（内容保留语义）
                    throw e;
                }
                if (attempt >= maxAttempts) {
                    throw e;
                }
                sleepBackoff(attempt);
            }
        }
    }

    @Override
    public LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink) {
        int[] emitted = {0};
        Consumer<String> counting = text -> {
            emitted[0]++;
            textSink.accept(text);
        };
        for (int attempt = 1; ; attempt++) {
            try {
                return delegate.streamTurn(request, counting);
            } catch (RetryableLlmException e) {
                if (emitted[0] > 0) {
                    throw e;
                }
                if (attempt >= maxAttempts) {
                    throw e;
                }
                sleepBackoff(attempt);
            }
        }
    }

    /** 第 attempt 次失败后的退避等待（×2 递增）。 */
    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(initialBackoffMs * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluginException("LLM 重试等待被中断", e);
        }
    }
}
