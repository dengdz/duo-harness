package dev.duo.harness.llm.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * OpenAI chat/completions 兼容适配器：DeepSeek/通义/Kimi/vLLM/Ollama 等同协议
 * provider 通用（baseUrl/apiKey/model 配置化，换模型不改代码）。
 *
 * <p>SSE 流式：响应体为 {@code data: {JSON}} 行序列，{@code data: [DONE]} 结束；
 * 逐行解析后经回调交付 {@code choices[0].delta.content} 增量。M3 不做重试——
 * 非 200 与网络错误以异常原样呈现（状态码与 provider 错误消息保留）。</p>
 */
public final class OpenAiCompatAdapter implements LlmAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmConfig config;
    private final HttpClient http;

    /** @param config LLM 调用配置（baseUrl/apiKey/model） */
    public OpenAiCompatAdapter(LlmConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void stream(ChatRequest request, Consumer<ChatChunk> onChunk) {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(chatCompletionsUrl()))
                // 响应头等待上限；流式 body 的读取不受此限（长回答不被误杀）
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request), StandardCharsets.UTF_8))
                .build();
        try {
            // try-with-resources：200 与错误路径都关响应流（ofInputStream 持有底层连接）
            HttpResponse<java.io.InputStream> response = http.send(httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw errorFrom(response.statusCode(), body);
                }
                streamLines(body, onChunk);
            }
        } catch (IOException e) {
            throw new PluginException("LLM 调用失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluginException("LLM 调用被中断", e);
        }
    }

    /** baseUrl 去尾斜杠后拼 OpenAI 兼容路径。 */
    private String chatCompletionsUrl() {
        String base = config.baseUrl().strip();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/chat/completions";
    }

    private String requestBody(ChatRequest request) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.systemPrompt());
        messages.addObject().put("role", "user").put("content", request.userMessage());
        return root.toString();
    }

    /** 逐行读 SSE：`data:` 行剥前缀解析，`[DONE]` 终止，`delta.content` 增量回调。 */
    private void streamLines(java.io.InputStream body, Consumer<ChatChunk> onChunk) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).strip();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    return;
                }
                deliverChunk(payload, onChunk);
            }
        }
    }

    private void deliverChunk(String payload, Consumer<ChatChunk> onChunk) {
        try {
            JsonNode node = JSON.readTree(payload);
            JsonNode content = node.path("choices").path(0).path("delta").path("content");
            if (!content.isMissingNode() && !content.isNull()) {
                onChunk.accept(new ChatChunk(content.asText()));
            }
        } catch (IOException e) {
            throw new PluginException("LLM 响应解析失败: " + payload, e);
        }
    }

    /** 非 200 响应转点名异常：状态码 + provider 错误消息（error.message）或原文。body 读取失败降级为占位文本。 */
    private PluginException errorFrom(int statusCode, java.io.InputStream body) {
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
        return new PluginException("LLM 调用失败: HTTP " + statusCode + " - " + providerMessage);
    }
}
