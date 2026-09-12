package dev.duo.harness.llm.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.RetryableLlmException;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.llm.ToolSpec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * OpenAI chat/completions 兼容适配器：DeepSeek/通义/Kimi/vLLM/Ollama 等同协议
 * provider 通用（baseUrl/apiKey/model 配置化，换模型不改代码）。
 *
 * <p>两条调用形态：{@link #stream}（直答，文本增量回调）与
 * {@link #streamTurn}（agent 循环，聚合文本与 tool_calls 分片后交付结构化结果）。
 * SSE 行协议：{@code data: {JSON}} 帧，{@code data: [DONE]} 终止。M5 无重试——
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
        try {
            HttpResponse<InputStream> response = send(request);
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw errorFrom(response.statusCode(), body);
                }
                streamLines(body, onChunk);
            }
        } catch (IOException e) {
            // 网络故障可重试（RetryingAdapter 据此退避重试）
            throw new RetryableLlmException("LLM 调用失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluginException("LLM 调用被中断", e);
        }
    }

    @Override
    public LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink) {
        try {
            HttpResponse<InputStream> response = send(request);
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw errorFrom(response.statusCode(), body);
                }
                return aggregateTurn(body, textSink);
            }
        } catch (IOException e) {
            // 网络故障可重试（RetryingAdapter 据此退避重试）
            throw new RetryableLlmException("LLM 调用失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluginException("LLM 调用被中断", e);
        }
    }

    /** 发送请求并返回响应（流式 body；调用方负责关闭）。 */
    private HttpResponse<InputStream> send(ChatRequest request)
            throws IOException, InterruptedException {
        HttpRequest httpRequest = buildHttpRequest(request);
        return http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
    }

    /** 构造 OpenAI 兼容请求（chat/completions，Bearer + 流式 + tools）。 */
    private HttpRequest buildHttpRequest(ChatRequest request) throws IOException {
        return HttpRequest.newBuilder()
                .uri(URI.create(chatCompletionsUrl()))
                // 响应头等待上限；流式 body 的读取不受此限（长回答不被误杀）
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request), StandardCharsets.UTF_8))
                .build();
    }

    private String chatCompletionsUrl() {
        String base = config.baseUrl().strip();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/chat/completions";
    }

    private String requestBody(ChatRequest request) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.systemPrompt());
        for (ChatMessage message : request.messages()) {
            ObjectNode node = messages.addObject()
                    .put("role", message.role().wire());
            if (message.role() == ChatMessage.Role.TOOL) {
                // 工具结果回填：协议要求携带 tool_call_id 关联模型发起的调用
                node.put("tool_call_id", message.toolCallId());
                node.put("content", message.content());
            } else if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                // assistant 工具调用消息：content 可空 + tool_calls 数组；
                // 思考模式的 reasoning_content 必须原样传回，缺失即被 provider 以 400 拒绝
                if (message.reasoningContent() != null) {
                    node.put("reasoning_content", message.reasoningContent());
                }
                node.put("content", message.content());
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCallRequest call : message.toolCalls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    callNode.putObject("function")
                            .put("name", call.name())
                            .put("arguments", call.argumentsJson());
                }
            } else {
                node.put("content", message.content());
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode function = tools.addObject()
                        .put("type", "function")
                        .putObject("function");
                function.put("name", spec.name());
                function.put("description", spec.description());
                function.put("parameters", JSON.readTree(spec.parametersJson()));
            }
        }
        return root.toString();
    }

    /** 逐行读 SSE：`data:` 行剥前缀解析，`[DONE]` 终止，`delta.content` 增量回调。 */
    private void streamLines(InputStream body, Consumer<ChatChunk> onChunk) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).strip();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    if ("[DONE]".equals(payload)) {
                        return;
                    }
                    continue;
                }
                JsonNode content = JSON.readTree(payload).path("choices").path(0).path("delta").path("content");
                if (!content.isMissingNode() && !content.isNull()) {
                    onChunk.accept(new ChatChunk(content.asText()));
                }
            }
        }
    }

    /** 聚合一轮流式响应：文本增量累积 + tool_calls 分片按 index 聚合 + 思考内容捕获（含 finish_reason 检出）。 */
    private LlmTurn aggregateTurn(InputStream body, Consumer<String> textSink) throws IOException {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        // 分片聚合容器：index → 分片内容（tool_calls 按到达序递增 index）
        Map<Integer, String> ids = new TreeMap<>();
        Map<Integer, StringBuilder> names = new TreeMap<>();
        Map<Integer, StringBuilder> arguments = new TreeMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).strip();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode delta = JSON.readTree(payload).path("choices").path(0).path("delta");
                JsonNode content = delta.path("content");
                if (!content.isMissingNode() && !content.isNull()) {
                    textSink.accept(content.asText());
                    text.append(content.asText());
                }
                JsonNode reasoningDelta = delta.path("reasoning_content");
                if (!reasoningDelta.isMissingNode() && !reasoningDelta.isNull()) {
                    reasoning.append(reasoningDelta.asText());
                }
                JsonNode calls = delta.path("tool_calls");
                if (calls.isArray()) {
                    for (JsonNode call : calls) {
                        int index = call.path("index").asInt(0);
                        JsonNode id = call.path("id");
                        if (!id.isMissingNode()) {
                            ids.putIfAbsent(index, id.asText());
                        }
                        JsonNode fnName = call.path("function").path("name");
                        if (!fnName.isMissingNode()) {
                            names.computeIfAbsent(index, k -> new StringBuilder()).append(fnName.asText());
                        }
                        JsonNode fnArgs = call.path("function").path("arguments");
                        if (!fnArgs.isMissingNode()) {
                            arguments.computeIfAbsent(index, k -> new StringBuilder()).append(fnArgs.asText());
                        }
                    }
                }
            }
        }

        for (Map.Entry<Integer, String> entry : ids.entrySet()) {
            int index = entry.getKey();
            toolCalls.add(new ToolCallRequest(entry.getValue(),
                    names.getOrDefault(index, new StringBuilder()).toString(),
                    arguments.getOrDefault(index, new StringBuilder()).toString()));
        }
        return new LlmTurn(text.toString(), toolCalls,
                reasoning.length() == 0 ? null : reasoning.toString());
    }

    /** 非 200 响应转点名异常：状态码 + provider 错误消息（error.message）或原文。body 读取失败降级为占位文本。
     *  瞬时服务端错误（429/502/503/504）标记为可重试；协议与凭证错误（400/401 等）不可重试。 */
    private PluginException errorFrom(int statusCode, InputStream body) {
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
