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
     * 按 provider 声明选型执行链（M24 工单 08，ADR-0026 决策七）：anthropic →
     * Anthropic-messages 适配器；openai-compat / deepseek / glm 走 OpenAI 兼容面
     * （deepseek/glm 仅思考等级映射策略不同，协议同面）。重试装饰同构。
     */
    public static LlmAdapter withRetry(LlmConfig config) {
        LlmAdapter raw = LlmConfig.PROVIDER_ANTHROPIC.equals(config.provider())
                ? new dev.duo.harness.llm.internal.AnthropicMessagesAdapter(config)
                : new dev.duo.harness.llm.internal.OpenAiCompatAdapter(config);
        return new RetryingAdapter(raw, config.retryMaxAttempts(), config.retryInitialBackoffMs());
    }
}
