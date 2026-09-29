package dev.duo.harness.llm.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.MessageImage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.TokenUsage;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.llm.ToolSpec;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
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
 * 非 200 与网络错误以异常原样呈现（状态码与 provider 错误消息保留）。
 * 调用骨架（发送/空闲守卫/错误翻译/重试分类）见 {@link StreamingHttpAdapter}。</p>
 */
public final class OpenAiCompatAdapter extends StreamingHttpAdapter {

    /** @param config LLM 调用配置（baseUrl/apiKey/model） */
    public OpenAiCompatAdapter(LlmConfig config) {
        super(config);
    }

    /** 构造 OpenAI 兼容请求（chat/completions，Bearer + 流式 + tools）。 */
    @Override
    protected HttpRequest buildHttpRequest(ChatRequest request) throws IOException {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(config().baseUrl(), "/chat/completions")))
                .timeout(HEADER_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config().apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request), StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 思考等级的三行映射（M24 工单 10，ADR-0026 决策六；Anthropic 行在自有适配器）：
     * <ul>
     *   <li>openai-compat：{@code reasoning_effort} 直传 low/medium/high；off 不带该
     *       字段（回到 provider 缺省行为）</li>
     *   <li>glm：{@code thinking.type} 开关二值化——off=disabled，low/medium/high 均
     *       enabled（GLM 无档位细粒度，标注在 /effort 响应）</li>
     *   <li>deepseek：永不带参数（OpenAI 兼容面无思考等级语义）——显式降级标注在
     *       /effort 命令响应，此处静默不带即正确形态</li>
     * </ul>
     * 未知档按 off 处理（命令面已挡非法值，此处兜底不炸）。
     */
    private void applyEffort(ObjectNode root, String effort) {
        String provider = config().provider() == null ? LlmConfig.PROVIDER_OPENAI_COMPAT
                : config().provider();
        boolean on = LlmConfig.EFFORT_LOW.equals(effort)
                || LlmConfig.EFFORT_MEDIUM.equals(effort)
                || LlmConfig.EFFORT_HIGH.equals(effort);
        switch (provider) {
            case LlmConfig.PROVIDER_GLM ->
                    root.putObject("thinking").put("type", on ? "enabled" : "disabled");
            case LlmConfig.PROVIDER_DEEPSEEK -> {
                // 显式降级行：档位不落任何请求参数——「思考请切 reasoner 模型」由 /effort 响应标注
            }
            default -> {
                if (on) {
                    root.put("reasoning_effort", effort);
                }
            }
        }
    }

    private String requestBody(ChatRequest request) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config().model());
        root.put("stream", true);
        // 流末 usage 统计帧（ADR-0009）：治理计量与状态展示优先用真实值，估算只兜底
        root.putObject("stream_options").put("include_usage", true);
        applyEffort(root, effortOf(request));
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.systemPrompt());
        for (ChatMessage message : request.messages()) {
            ObjectNode node = messages.addObject()
                    .put("role", message.role().wire());
            if (message.role() == ChatMessage.Role.TOOL) {
                // 工具结果回填：协议要求携带 tool_call_id 关联模型发起的调用
                node.put("tool_call_id", message.toolCallId());
                if (message.images() != null && !message.images().isEmpty()) {
                    // read_image 结果回填（M21 工单 05）：content 数组（文本 + image_url）
                    ArrayNode parts = node.putArray("content");
                    if (!message.content().isEmpty()) {
                        parts.addObject().put("type", "text").put("text", message.content());
                    }
                    for (MessageImage image : message.images()) {
                        appendImagePart(parts, image);
                    }
                } else {
                    node.put("content", message.content());
                }
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
            } else if (message.images() != null && !message.images().isEmpty()) {
                // 多部件 content（M21 工单 05，OpenAI 兼容形态）：文本 + image_url（data URI）
                ArrayNode parts = node.putArray("content");
                if (!message.content().isEmpty()) {
                    parts.addObject().put("type", "text").put("text", message.content());
                }
                for (MessageImage image : message.images()) {
                    appendImagePart(parts, image);
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

    /** 直答帧解释：`delta.content` 增量回调；`[DONE]` 终止。 */
    @Override
    protected void streamFrames(InputStream body, Consumer<ChatChunk> onChunk) throws IOException {
        readSse(body, payload -> {
            if ("[DONE]".equals(payload)) {
                return true;
            }
            JsonNode content = JSON.readTree(payload)
                    .path("choices").path(0).path("delta").path("content");
            if (!content.isMissingNode() && !content.isNull()) {
                onChunk.accept(new ChatChunk(content.asText()));
            }
            return false;
        });
    }

    /** 聚合一轮流式响应：文本增量累积 + tool_calls 分片按 index 聚合 + 思考内容捕获 + 流末 usage 统计。 */
    @Override
    protected LlmTurn aggregateTurn(InputStream body, Consumer<String> textSink,
                                    Consumer<String> reasoningSink) throws IOException {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        // lambda 帧处理器内的可变状态用单元素容器承载（usage 覆盖式取最新）
        TokenUsage[] usage = {null};
        // 分片聚合容器：index → 分片内容（tool_calls 按到达序递增 index）
        Map<Integer, String> ids = new TreeMap<>();
        Map<Integer, StringBuilder> names = new TreeMap<>();
        Map<Integer, StringBuilder> arguments = new TreeMap<>();

        readSse(body, payload -> {
            if ("[DONE]".equals(payload)) {
                return true;
            }
            JsonNode frame = JSON.readTree(payload);
            JsonNode delta = frame.path("choices").path(0).path("delta");
            JsonNode content = delta.path("content");
            if (!content.isMissingNode() && !content.isNull()) {
                textSink.accept(content.asText());
                text.append(content.asText());
            }
            JsonNode reasoningDelta = delta.path("reasoning_content");
            if (!reasoningDelta.isMissingNode() && !reasoningDelta.isNull()) {
                reasoning.append(reasoningDelta.asText());
                reasoningSink.accept(reasoningDelta.asText());
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
            // usage 帧（choices 空数组 + 顶层 usage）：覆盖式取最新——有的 provider
            // 附在 finish chunk、有的独立成帧，两种形态统一为"最后一次出现为准"
            JsonNode usageNode = frame.path("usage");
            if (usageNode.isObject()) {
                // 缓存命中（M25 工单 06 用量透出）：openai-compat 形态
                // prompt_tokens_details.cached_tokens；deepseek 形态
                // prompt_cache_hit_tokens（同一适配器承载两 provider，取大者防双报）
                // ——provider 自动前缀缓存（无断点参数，静默享受）；字段缺席 = 0
                long cached = Math.max(
                        usageNode.path("prompt_tokens_details").path("cached_tokens").asLong(0),
                        usageNode.path("prompt_cache_hit_tokens").asLong(0));
                usage[0] = new TokenUsage(
                        usageNode.path("prompt_tokens").asLong(0),
                        usageNode.path("completion_tokens").asLong(0),
                        usageNode.path("total_tokens").asLong(0),
                        cached);
            }
            return false;
        });

        for (Map.Entry<Integer, String> entry : ids.entrySet()) {
            int index = entry.getKey();
            toolCalls.add(new ToolCallRequest(entry.getValue(),
                    names.getOrDefault(index, new StringBuilder()).toString(),
                    arguments.getOrDefault(index, new StringBuilder()).toString()));
        }
        return new LlmTurn(text.toString(), toolCalls,
                reasoning.length() == 0 ? null : reasoning.toString(), usage[0]);
    }

    /**
     * 图片部件序列化（M21 工单 06）：files 投递形态输出 file 引用部件
     * （DeepSeek 形态 {@code {"type":"file","file":{"file_id":…}}}），
     * inline 形态输出 image_url data URI（任何 OpenAI 兼容端点可用）。
     */
    private static void appendImagePart(ArrayNode parts, MessageImage image) {
        if (image.deliveredAsFile()) {
            parts.addObject().put("type", "file").putObject("file")
                    .put("file_id", image.fileId());
            return;
        }
        parts.addObject().put("type", "image_url").putObject("image_url")
                .put("url", image.dataUri());
    }
}
