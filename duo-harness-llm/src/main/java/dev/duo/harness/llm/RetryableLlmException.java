package dev.duo.harness.llm;

import dev.duo.harness.core.api.PluginException;

/**
 * 可重试的 LLM 调用异常：网络故障与瞬时服务端错误（429/502/503/504）。
 *
 * <p>语义标记——{@link RetryingAdapter} 只对它重试；协议与凭证错误（400/401 等）
 * 是普通 {@link PluginException}，重试只会掩盖问题，必须立即暴露。</p>
 */
public class RetryableLlmException extends PluginException {

    public RetryableLlmException(String message) {
        super(message);
    }

    public RetryableLlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
