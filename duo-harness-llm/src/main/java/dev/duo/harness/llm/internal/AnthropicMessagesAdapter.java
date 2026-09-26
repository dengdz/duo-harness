package dev.duo.harness.llm.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.CacheControl;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.RetryableLlmException;
import dev.duo.harness.llm.TokenUsage;
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
 * Anthropic messages 协议适配器（M24 工单 08，ADR-0026 决策七）：与
 * {@link OpenAiCompatAdapter} 并列的仅有的两个协议实现——SSE 流式、system 单列、
 * tool_use/tool_result 块双向映射、{@code x-api-key} + {@code anthropic-version}
 * 鉴权头。provider 声明（llm.provider=anthropic）驱动选型，详见 {@link LlmConfig}。
 *
 * <p>协议映射要点：TOOL 结果回填映射为 <b>user 角色</b>的 {@code tool_result} 块，
 * 连续多条工具结果合并为单条 user 消息（协议要求 user/assistant 交替）；assistant
 * 的工具调用映射为 {@code tool_use} 块（input 为 argumentsJson 解析后的对象）。
 * 首期限制：图片仅 inline data URI 形态（files 投递为 DeepSeek 专有，Anthropic 面
 * 不支持）；max_tokens 协议必填，off 档缺省 8192、思考档随 budget 抬升（见
 * {@link #applyEffort}——M24 工单 10）。</p>
 *
 * <p>SSE 帧序：{@code message_start}（input_tokens）→ {@code content_block_start}
 * （tool_use 携 id/name）→ {@code content_block_delta}（text_delta / input_json_delta）
 * → {@code content_block_stop} → {@code message_delta}（output_tokens）→
 * {@code message_stop}。空闲超时/重试语义与 OpenAI 兼容面同构。</p>
 */
public final class AnthropicMessagesAdapter implements LlmAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 协议版本头（Anthropic messages API 当前稳定版）。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** max_tokens 协议必填缺省。 */
    private static final int DEFAULT_MAX_TOKENS = 8192;

    /** 思考档 max_tokens 抬升余量（协议要求 max_tokens &gt; budget_tokens）。 */
    private static final int MAX_TOKENS_HEADROOM = 1024;

    private final LlmConfig config;
    private final HttpClient http;

    /** @param config LLM 调用配置（baseUrl/apiKey/model + provider=anthropic） */
    public AnthropicMessagesAdapter(LlmConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void stream(ChatRequest request, Consumer<ChatChunk> onChunk) {
        boolean[] delivered = {false};
        try {
            HttpResponse<InputStream> response = send(request);
            try (InputStream body = idleGuarded(response.body())) {
                if (response.statusCode() != 200) {
                    throw errorFrom(response.statusCode(), body);
                }
                streamText(body, chunk -> {
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
    public LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink) {
        boolean[] delivered = {false};
        try {
            HttpResponse<InputStream> response = send(request);
            try (InputStream body = idleGuarded(response.body())) {
                if (response.statusCode() != 200) {
                    throw errorFrom(response.statusCode(), body);
                }
                return aggregateTurn(body, text -> {
                    delivered[0] = true;
                    textSink.accept(text);
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

    private InputStream idleGuarded(InputStream body) {
        return new IdleTimeoutStream(body, config.streamIdleTimeoutMs());
    }

    /** 空闲超时二分语义（与 OpenAI 兼容面同构）：未交付可重试，已交付保留已输出。 */
    private PluginException idleOutcome(StreamIdleTimeoutException e, boolean delivered) {
        if (delivered) {
            return new PluginException("LLM 流式空闲超时，已输出内容保留: " + e.getMessage(), e);
        }
        return new RetryableLlmException("LLM 流式空闲超时: " + e.getMessage(), e);
    }

    private HttpResponse<InputStream> send(ChatRequest request)
            throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(messagesUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request), StandardCharsets.UTF_8))
                .build();
        return http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
    }

    private String messagesUrl() {
        String base = config.baseUrl().strip();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/v1/messages";
    }

    /**
     * 构造 messages 请求体：system 单列 + user/assistant 交替 + tool_use/tool_result 块映射。
     * cacheControl（M25 工单 06）：system 按三级划分组块并逐块打 {@code cache_control:
     * ephemeral} 断点（身份前缀/稳定身份），最后一条消息追加动态段断点——块数组与
     * 字符串形态语义等价（单块无断点时保持字符串，兼容无缓存路径）。
     */
    private String requestBody(ChatRequest request) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        applyEffort(root, effortOf(request));
        if (!request.systemPrompt().isBlank()) {
            CacheControl.Segments segments = CacheControl.split(request.systemPrompt());
            ArrayNode systemBlocks = JSON.createArrayNode();
            if (segments.stableBody() != null) {
                // 两段形态（身份前缀 + 稳定身份）：块数组各打 ephemeral（断点 1/2），
                // 块间以 \n\n 还原——与单段字符串逐字节等价（缓存键不受形态影响）。
                // 单段形态退字符串——旧路径零变化（无断点比错误断点安全）
                if (segments.identityPrefix() != null) {
                    systemBlocks.addObject()
                            .put("type", "text").put("text", segments.identityPrefix())
                            .putObject("cache_control").put("type", "ephemeral");
                }
                String body = segments.stableBody();
                if (segments.identityPrefix() != null) {
                    body = "\n\n" + body;
                }
                systemBlocks.addObject()
                        .put("type", "text").put("text", body)
                        .putObject("cache_control").put("type", "ephemeral");
                root.set("system", systemBlocks);
            } else {
                root.put("system", request.systemPrompt());
            }
        }
        ArrayNode messages = root.putArray("messages");
        List<ChatMessage> history = request.messages();
        for (int i = 0; i < history.size(); i++) {
            ChatMessage message = history.get(i);
            if (message.role() == ChatMessage.Role.TOOL) {
                // 连续工具结果合并为单条 user 消息（协议要求 user/assistant 交替）
                int end = i;
                while (end < history.size() && history.get(end).role() == ChatMessage.Role.TOOL) {
                    end++;
                }
                messages.add(toolResultMessage(history.subList(i, end)));
                i = end - 1;
                continue;
            }
            messages.add(userOrAssistantMessage(message));
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("name", spec.name());
                tool.put("description", spec.description());
                tool.set("input_schema", JSON.readTree(spec.parametersJson()));
            }
        }
        // 动态段断点（三级之末）：末条消息的 content 块上打 ephemeral——会话历史逐轮
        // 追加，末条断点让"到上一轮为止的对话"可缓存（M25 工单 06）。协议挂载位是
        // content block：字符串 content 先组块再挂（字符串上挂顶层字段会被忽略）
        if (!messages.isEmpty() && messages.get(messages.size() - 1).isObject()) {
            ObjectNode last = (ObjectNode) messages.get(messages.size() - 1);
            JsonNode content = last.get("content");
            if (content != null && content.isTextual()) {
                // 组块必须带判别字段 type（BUG-20260926-01：漏 type 遭 422——
                // 协议块合法性按官方 schema 自查，mock 回显测不出）
                ArrayNode blocks = JSON.createArrayNode();
                blocks.addObject().put("type", "text").set("text", content);
                last.set("content", blocks);
            }
            ArrayNode contentBlocks = last.withArray("content");
            if (contentBlocks.size() > 0 && contentBlocks.get(contentBlocks.size() - 1).isObject()) {
                ((ObjectNode) contentBlocks.get(contentBlocks.size() - 1))
                        .putObject("cache_control").put("type", "ephemeral");
            }
        }
        return root.toString();
    }

    /**
     * 本次请求生效的思考等级：请求级覆盖优先（辅助性请求强制 low），否则取配置档
     * （/effort 切换经 withEffort 换链生效）。
     */
    private String effortOf(ChatRequest request) {
        return request.effortOverride() != null ? request.effortOverride() : config.effort();
    }

    /**
     * Anthropic 行映射（M24 工单 10，ADR-0026 决策六）：off → 不带 thinking 节点
     * （思考关闭）；low/medium/high → {@code thinking: {type: enabled, budget_tokens}}
     * （2048 / 8192 / 16384），且 max_tokens 同步抬到 budget + {@link #MAX_TOKENS_HEADROOM}
     * （协议要求 max_tokens &gt; budget_tokens——成本仍由实际用量决定，抬上限不改计费本质）。
     * 未知档按 off 处理（命令面已挡非法值，此处兜底不炸）。
     */
    private static void applyEffort(ObjectNode root, String effort) {
        if (effort == null) {
            effort = LlmConfig.DEFAULT_EFFORT;
        }
        int budget = switch (effort) {
            case LlmConfig.EFFORT_LOW -> LlmConfig.ANTHROPIC_BUDGET_LOW;
            case LlmConfig.EFFORT_MEDIUM -> LlmConfig.ANTHROPIC_BUDGET_MEDIUM;
            case LlmConfig.EFFORT_HIGH -> LlmConfig.ANTHROPIC_BUDGET_HIGH;
            default -> 0;
        };
        if (budget <= 0) {
            root.put("max_tokens", DEFAULT_MAX_TOKENS);
            return;
        }
        root.putObject("thinking").put("type", "enabled").put("budget_tokens", budget);
        root.put("max_tokens", Math.max(DEFAULT_MAX_TOKENS, budget + MAX_TOKENS_HEADROOM));
    }

    /**
     * user/assistant 消息映射：assistant 携工具调用时输出 thinking（如有）+ text +
     * tool_use 块数组——扩展思考协议要求 thinking 块（含 signature）在工具循环的
     * 下一轮请求中原样回传（M24 工单 10 审查修复）；块以 reasoningContent 通道的
     * JSON 形态承载（采集见 {@link #aggregateTurn}）。
     */
    private ObjectNode userOrAssistantMessage(ChatMessage message) throws IOException {
        ObjectNode node = JSON.createObjectNode();
        boolean assistant = message.role() == ChatMessage.Role.ASSISTANT;
        node.put("role", assistant ? "assistant" : "user");
        if (assistant && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            ArrayNode content = node.putArray("content");
            if (message.reasoningContent() != null && message.reasoningContent().startsWith("{")) {
                content.add(JSON.readTree(message.reasoningContent()));
            }
            if (!message.content().isEmpty()) {
                content.addObject().put("type", "text").put("text", message.content());
            }
            for (ToolCallRequest call : message.toolCalls()) {
                ObjectNode use = content.addObject();
                use.put("type", "tool_use");
                use.put("id", call.id());
                use.put("name", call.name());
                use.set("input", JSON.readTree(call.argumentsJson()));
            }
            return node;
        }
        if (!assistant && message.images() != null && !message.images().isEmpty()) {
            ArrayNode content = node.putArray("content");
            if (!message.content().isEmpty()) {
                content.addObject().put("type", "text").put("text", message.content());
            }
            for (var image : message.images()) {
                appendImageBlock(content, image);
            }
            return node;
        }
        node.put("content", message.content());
        return node;
    }

    /** 连续 TOOL 结果 → 单条 user 消息（多个 tool_result 块，按 tool_use_id 关联）。 */
    private ObjectNode toolResultMessage(List<ChatMessage> toolMessages) {
        ObjectNode node = JSON.createObjectNode();
        node.put("role", "user");
        ArrayNode content = node.putArray("content");
        for (ChatMessage tool : toolMessages) {
            ObjectNode result = content.addObject();
            result.put("type", "tool_result");
            result.put("tool_use_id", tool.toolCallId());
            result.put("content", tool.content());
        }
        return node;
    }

    /** inline 图片块（data URI 拆 media_type + base64）；files 投递形态 Anthropic 面不支持，跳过。 */
    private static void appendImageBlock(ArrayNode content, dev.duo.harness.llm.MessageImage image) {
        if (image.deliveredAsFile()) {
            return; // files 投递为 DeepSeek 专有——Anthropic 面跳过（已知限制，见类注释）
        }
        String dataUri = image.dataUri();
        int base64Mark = dataUri.indexOf(";base64,");
        if (base64Mark < 0) {
            return; // 非 base64 data URI：不支持，跳过（fail-closed 不猜测）
        }
        String mediaType = dataUri.substring("data:".length(), base64Mark);
        String data = dataUri.substring(base64Mark + ";base64,".length());
        content.addObject().put("type", "image").putObject("source")
                .put("type", "base64")
                .put("media_type", mediaType)
                .put("data", data);
    }

    /** 直答流式读取：仅文本增量（text_delta）；error 帧转异常上报（不静默吞）。 */
    private void streamText(InputStream body, Consumer<ChatChunk> onChunk) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                JsonNode frame = JSON.readTree(line.substring("data:".length()).strip());
                String type = frame.path("type").asText("");
                if ("error".equals(type)) {
                    throw new PluginException("LLM 流式错误: "
                            + frame.path("error").path("message").asText("未知错误"));
                }
                if ("message_stop".equals(type)) {
                    return;
                }
                String text = textDelta(frame);
                if (!text.isEmpty()) {
                    onChunk.accept(new ChatChunk(text));
                }
            }
        }
    }

    /** 聚合一轮：text_delta 累积 + tool_use 块聚合（start 携 id/name，input_json_delta 拼参数）+ thinking 块 + usage。 */
    private LlmTurn aggregateTurn(InputStream body, Consumer<String> textSink) throws IOException {
        StringBuilder text = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        // thinking 块聚合容器（M24 工单 10 审查修复）：扩展思考协议要求工具循环的下一轮
        // 请求原样回传 assistant 回合的 thinking 块（含 signature，缺失即 400 签名错误）
        // ——聚合为单块 JSON 经 reasoningContent 通道持久化（tool/call 事件既有通道，
        // OpenAI 兼容面 reasoning_content 为纯文本不受影响：块 JSON 以 "{" 开头可鉴别）
        StringBuilder thinkingText = new StringBuilder();
        StringBuilder thinkingSignature = new StringBuilder();
        boolean thinkingBlock = false;
        String redactedBlockJson = null;
        // tool_use 块聚合容器：content block index → id/name/参数分片
        Map<Integer, String> ids = new TreeMap<>();
        Map<Integer, String> names = new TreeMap<>();
        Map<Integer, StringBuilder> arguments = new TreeMap<>();
        long promptTokens = 0;
        long cacheReadTokens = 0;
        long completionTokens = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                JsonNode frame = JSON.readTree(line.substring("data:".length()).strip());
                String type = frame.path("type").asText("");
                if ("message_stop".equals(type)) {
                    break;
                }
                if ("error".equals(type)) {
                    throw new PluginException("LLM 流式错误: "
                            + frame.path("error").path("message").asText("未知错误"));
                }
                if ("message_start".equals(type)) {
                    promptTokens = frame.path("message").path("usage").path("input_tokens").asLong(0);
                    cacheReadTokens = frame.path("message").path("usage")
                            .path("cache_read_input_tokens").asLong(0);
                    continue;
                }
                if ("message_delta".equals(type)) {
                    completionTokens = frame.path("usage").path("output_tokens").asLong(completionTokens);
                    continue;
                }
                if ("content_block_start".equals(type)) {
                    JsonNode block = frame.path("content_block");
                    String blockType = block.path("type").asText();
                    if ("tool_use".equals(blockType)) {
                        int index = frame.path("index").asInt(0);
                        ids.put(index, block.path("id").asText());
                        names.put(index, block.path("name").asText());
                    } else if ("thinking".equals(blockType)) {
                        thinkingBlock = true;
                    } else if ("redacted_thinking".equals(blockType)) {
                        // 不可逆加密思考块（安全过滤触发）：无 delta，整块原样保留回传
                        redactedBlockJson = block.toString();
                    }
                    continue;
                }
                if ("content_block_delta".equals(type)) {
                    JsonNode delta = frame.path("delta");
                    String deltaType = delta.path("type").asText("");
                    if ("text_delta".equals(deltaType)) {
                        String piece = delta.path("text").asText();
                        textSink.accept(piece);
                        text.append(piece);
                    } else if ("input_json_delta".equals(deltaType)) {
                        arguments.computeIfAbsent(frame.path("index").asInt(0),
                                k -> new StringBuilder()).append(delta.path("partial_json").asText());
                    } else if ("thinking_delta".equals(deltaType)) {
                        thinkingText.append(delta.path("thinking").asText());
                    } else if ("signature_delta".equals(deltaType)) {
                        thinkingSignature.append(delta.path("signature").asText());
                    }
                }
            }
        }

        for (Map.Entry<Integer, String> entry : ids.entrySet()) {
            toolCalls.add(new ToolCallRequest(entry.getValue(),
                    names.getOrDefault(entry.getKey(), ""),
                    arguments.getOrDefault(entry.getKey(), new StringBuilder()).toString()));
        }
        TokenUsage usage = promptTokens == 0 && completionTokens == 0 ? null
                : new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens,
                        cacheReadTokens);
        String reasoning = null;
        if (redactedBlockJson != null) {
            reasoning = redactedBlockJson;
        } else if (thinkingBlock && thinkingText.length() > 0) {
            reasoning = JSON.createObjectNode()
                    .put("type", "thinking")
                    .put("thinking", thinkingText.toString())
                    .put("signature", thinkingSignature.toString())
                    .toString();
        }
        return new LlmTurn(text.toString(), toolCalls, reasoning, usage);
    }

    /** text_delta 增量提取（无则空串）。 */
    private static String textDelta(JsonNode frame) {
        JsonNode text = frame.path("delta").path("text");
        return text.isMissingNode() || text.isNull() ? "" : text.asText();
    }

    /** 非 200 响应转点名异常：状态码 + provider 错误消息（error.message）；429/5xx 可重试。 */
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
