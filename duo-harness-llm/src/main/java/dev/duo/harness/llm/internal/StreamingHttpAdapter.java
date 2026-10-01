package dev.duo.harness.llm.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.RetryableLlmException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * SSE 流式 HTTP 适配器骨架（C2 工单 04）：承载两个协议实现（OpenAI 兼容面 /
 * Anthropic messages 面）完全同构的部分——请求发送、空闲守卫、空闲超时二分语义、
 * 错误翻译与可重试分类、URL 尾斜杠归一、SSE 行循环；协议差异（鉴权头、请求体、
 * 帧解释）由子类实现。此前两适配器逐字双份维护（M16 空闲超时、M25 usage 语义的
 * 修复被迫双处同步，漏一处即协议面行为分叉），骨架收编后同构行为由结构保证，
 * 帧解释是唯一分叉点（行为同构由 AdapterParityTest 对拍锁死）。
 */
abstract class StreamingHttpAdapter implements LlmAdapter {

    /** 共用 JSON 读写（两协议面的帧与请求体序列化）。 */
    protected static final ObjectMapper JSON = new ObjectMapper();

    /** 响应头等待上限；流式 body 的读取不受此限（长回答不被误杀）。 */
    protected static final Duration HEADER_TIMEOUT = Duration.ofSeconds(10);

    private static final String SSE_DATA_PREFIX = "data:";

    private final LlmConfig config;
    private final HttpClient http;

    protected StreamingHttpAdapter(LlmConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    protected final LlmConfig config() {
        return config;
    }

    @Override
    public final void stream(ChatRequest request, Consumer<ChatChunk> onChunk) {
        boolean[] delivered = {false};
        try {
            HttpResponse<InputStream> response = send(request);
            try (InputStream body = idleGuarded(response.body())) {
                if (response.statusCode() != 200) {
                    throw errorFrom(response.statusCode(), body);
                }
                streamFrames(body, chunk -> {
                    delivered[0] = true;
                    onChunk.accept(chunk);
                });
            }
        } catch (StreamIdleTimeoutException e) {
            throw idleOutcome(e, delivered[0]);
        } catch (IOException e) {
            // 网络故障可重试（RetryingAdapter 据此退避重试）
            throw new RetryableLlmException("LLM 调用失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluginException("LLM 调用被中断", e);
        }
    }

    @Override
    public final LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink) {
        // 缺省思考通道：无交付目标即丢弃增量，聚合行为与两参形态完全一致
        // （具名单例——三参路径的「已交付」置位以非丢弃形态为准，身份判别用 ==）
        return streamTurn(request, textSink, DISCARD_REASONING);
    }

    /** 两参形态的缺省思考通道（丢弃 sink 单例）：丢弃形态的思考增量不构成「已流出」。 */
    private static final Consumer<String> DISCARD_REASONING = r -> { };

    @Override
    public final LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink,
                                    Consumer<String> reasoningSink) {
        boolean[] delivered = {false};
        boolean reasoningLive = reasoningSink != DISCARD_REASONING; // 真实消费方才计「已流出」
        try {
            HttpResponse<InputStream> response = send(request);
            try (InputStream body = idleGuarded(response.body())) {
                if (response.statusCode() != 200) {
                    throw errorFrom(response.statusCode(), body);
                }
                return aggregateTurn(body, text -> {
                    delivered[0] = true;
                    textSink.accept(text);
                }, reasoning -> {
                    // 思考增量已交付（真实消费方）同样进入「已流出保留」语义——
                    // 思考期超时不可重试，否则重试会重复投递思考增量（M29 审查发现）
                    if (reasoningLive) delivered[0] = true;
                    reasoningSink.accept(reasoning);
                });
            }
        } catch (StreamIdleTimeoutException e) {
            throw idleOutcome(e, delivered[0]);
        } catch (IOException e) {
            throw new RetryableLlmException("LLM 调用失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluginException("LLM 调用被中断", e);
        }
    }

    /** 直答流式读取（帧解释）：仅文本增量回调。 */
    protected abstract void streamFrames(InputStream body, Consumer<ChatChunk> onChunk) throws IOException;

    /** 聚合一轮流式响应（帧解释）：文本增量 + 思考增量（M29 工单 06 实时通道）+ 工具调用 + usage 统计。 */
    protected abstract LlmTurn aggregateTurn(InputStream body, Consumer<String> textSink,
                                             Consumer<String> reasoningSink) throws IOException;

    /** 构造协议请求（URI、鉴权头、请求体由子类决定）。 */
    protected abstract HttpRequest buildHttpRequest(ChatRequest request) throws IOException;

    /** body 包装空闲超时（M16 工单 05）：单点覆盖 stream 与 streamTurn 两条读取路径。 */
    private InputStream idleGuarded(InputStream body) {
        return new IdleTimeoutStream(body, config.streamIdleTimeoutMs());
    }

    /**
     * 空闲超时的二分语义（与重试链的流式安全对齐）：尚未交付任何增量的超时按
     * 可重试错误上抛（RetryingAdapter 退避重试）；已交付增量后的超时不可重试
     * （重试会导致内容重复），已输出文本按契约保留。
     */
    private static PluginException idleOutcome(StreamIdleTimeoutException e, boolean delivered) {
        if (delivered) {
            return new PluginException("LLM 流式空闲超时，已输出内容保留: " + e.getMessage(), e);
        }
        return new RetryableLlmException("LLM 流式空闲超时: " + e.getMessage(), e);
    }

    /** 发送请求并返回响应（流式 body；调用方负责关闭）。 */
    private HttpResponse<InputStream> send(ChatRequest request)
            throws IOException, InterruptedException {
        return http.send(buildHttpRequest(request), HttpResponse.BodyHandlers.ofInputStream());
    }

    /** baseUrl 尾斜杠归一 + 协议路径拼接。 */
    protected static String url(String baseUrl, String path) {
        String base = baseUrl.strip();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
    }

    /** 本次请求生效的思考等级：请求级覆盖优先（辅助性请求强制 low），否则取配置档。 */
    protected final String effortOf(ChatRequest request) {
        return request.effortOverride() != null ? request.effortOverride() : config.effort();
    }

    /**
     * SSE 行循环骨架：剥 {@code data:} 前缀交帧处理器；空行跳过；处理器返回 true
     * 即终止（{@code [DONE]} / {@code message_stop}）。
     */
    protected static void readSse(InputStream body, SseFrameHandler handler) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(SSE_DATA_PREFIX)) {
                    continue;
                }
                String payload = line.substring(SSE_DATA_PREFIX.length()).strip();
                if (payload.isEmpty() || handler.accept(payload)) {
                    return;
                }
            }
        }
    }

    /** 单帧处理器：返回 true 终止读取。 */
    @FunctionalInterface
    protected interface SseFrameHandler {
        boolean accept(String payload) throws IOException;
    }

    /**
     * 非 200 响应转点名异常：状态码 + provider 错误消息（error.message）或原文。
     * body 读取失败降级为占位文本。瞬时服务端错误（429/502/503/504）标记为可重试；
     * 协议与凭证错误（400/401 等）不可重试。
     */
    protected static PluginException errorFrom(int statusCode, InputStream body) {
        String text;
        try {
            text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "<响应体不可读>";
        }
        String providerMessage = text;
        try {
            JsonNode message = JSON.readTree(text).path("error").path("message");
            if (!message.isMissingNode()) {
                providerMessage = message.asText();
            }
        } catch (IOException ignored) {
            // 非 JSON 错误体：保留原文
        }
        PluginException exception = new PluginException(
                "LLM 调用失败: HTTP " + statusCode + " - " + providerMessage);
        if (statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504) {
            return new RetryableLlmException(exception.getMessage());
        }
        return exception;
    }
}
