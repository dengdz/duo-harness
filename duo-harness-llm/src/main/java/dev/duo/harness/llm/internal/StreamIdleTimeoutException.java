package dev.duo.harness.llm.internal;

import java.io.IOException;

/**
 * 流式空闲超时（M16 工单 05）：连续 idleTimeoutMs 无新字节的读取中止。
 *
 * <p>独立类型而非裸 IOException——适配器据此区分"尚无增量的超时"（可重试）与
 * "已交付增量后的超时"（保留已输出、不重试），是二分语义的载体。</p>
 */
final class StreamIdleTimeoutException extends IOException {

    StreamIdleTimeoutException(String message) {
        super(message);
    }
}
