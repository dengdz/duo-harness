package dev.duo.harness.llm;

/**
 * LLM 执行链的契约包静态工厂（呈现位装配单点 M11-01 建立）：把 OpenAI 兼容
 * 适配器 + 按配置重试的组装留在本模块——{@code llm.internal} 的协议实现细节
 * 不外泄给下游（模块划分约定：internal 包名即"勿用"契约）。
 */
public final class LlmAdapters {

    private LlmAdapters() {
    }

    /**
     * 缺省执行链：OpenAI 兼容适配器 + 重试装饰（重试参数来自 {@code llm.retry} 段，
     * 缺省值与 {@link RetryingAdapter} 默认一致——未配置该段时行为与裸装饰器相同）。
     */
    public static LlmAdapter openAiCompatWithRetry(LlmConfig config) {
        return new RetryingAdapter(new dev.duo.harness.llm.internal.OpenAiCompatAdapter(config),
                config.retryMaxAttempts(), config.retryInitialBackoffMs());
    }
}
