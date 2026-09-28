package dev.duo.harness.llm.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.CacheControl;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
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
 * Anthropic messages 协议适配器（M24 工单 08，ADR-0026 决策七）：与
 * {@link OpenAiCompatAdapter} 并列的仅有的两个协议实现——SSE 流式、system 单列、
 * tool_use/tool_result 块双向映射、{@code x-api-key} + {@code anthropic-version}
 * 鉴权头。provider 声明（llm.provider=anthropic）驱动选型，详见 {@link LlmConfig}。
 * 调用骨架（发送/空闲守卫/错误翻译/重试分类）见 {@link StreamingHttpAdapter}。
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
public final class AnthropicMessagesAdapter extends StreamingHttpAdapter {

    /** 协议版本头（Anthropic messages API 当前稳定版）。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** max_tokens 协议必填缺省。 */
    private static final int DEFAULT_MAX_TOKENS = 8192;

    /** 思考档 max_tokens 抬升余量（协议要求 max_tokens &gt; budget_tokens）。 */
    private static final int MAX_TOKENS_HEADROOM = 1024;

    /** @param config LLM 调用配置（baseUrl/apiKey/model + provider=anthropic） */
    public AnthropicMessagesAdapter(LlmConfig config) {
        super(config);
    }

    @Override
    protected HttpRequest buildHttpRequest(ChatRequest request) throws IOException {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(config().baseUrl(), "/v1/messages")))
                .timeout(HEADER_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-api-key", config().apiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request), StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 构造 messages 请求体：system 单列 + user/assistant 交替 + tool_use/tool_result 块映射。
     * cacheControl（M25 工单 06）：system 按三级划分组块并逐块打 {@code cache_control:
     * ephemeral} 断点（身份前缀/稳定身份），最后一条消息追加动态段断点——块数组与
     * 字符串形态语义等价（单块无断点时保持字符串，兼容无缓存路径）。
     */
    private String requestBody(ChatRequest request) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", config().model());
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

    /** 直答帧解释：仅文本增量（text_delta）；error 帧转异常上报（不静默吞）。 */
    @Override
    protected void streamFrames(InputStream body, Consumer<ChatChunk> onChunk) throws IOException {
        readSse(body, payload -> {
            JsonNode frame = JSON.readTree(payload);
            String type = frame.path("type").asText("");
            if ("error".equals(type)) {
                throw new PluginException("LLM 流式错误: "
                        + frame.path("error").path("message").asText("未知错误"));
            }
            if ("message_stop".equals(type)) {
                return true;
            }
            String text = textDelta(frame);
            if (!text.isEmpty()) {
                onChunk.accept(new ChatChunk(text));
            }
            return false;
        });
    }

    /** 聚合一轮：text_delta 累积 + tool_use 块聚合（start 携 id/name，input_json_delta 拼参数）+ thinking 块 + usage。 */
    @Override
    protected LlmTurn aggregateTurn(InputStream body, Consumer<String> textSink) throws IOException {
        StringBuilder text = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        // thinking 块聚合容器（M24 工单 10 审查修复）：扩展思考协议要求工具循环的下一轮
        // 请求原样回传 assistant 回合的 thinking 块（含 signature，缺失即 400 签名错误）
        // ——聚合为单块 JSON 经 reasoningContent 通道持久化（tool/call 事件既有通道，
        // OpenAI 兼容面 reasoning_content 为纯文本不受影响：块 JSON 以 "{" 开头可鉴别）
        StringBuilder thinkingText = new StringBuilder();
        StringBuilder thinkingSignature = new StringBuilder();
        // lambda 帧处理器内的可变状态用单元素容器承载
        boolean[] thinkingBlock = {false};
        String[] redactedBlockJson = {null};
        // tool_use 块聚合容器：content block index → id/name/参数分片
        Map<Integer, String> ids = new TreeMap<>();
        Map<Integer, String> names = new TreeMap<>();
        Map<Integer, StringBuilder> arguments = new TreeMap<>();
        long[] promptTokens = {0};
        long[] cacheReadTokens = {0};
        long[] completionTokens = {0};

        readSse(body, payload -> {
            JsonNode frame = JSON.readTree(payload);
            String type = frame.path("type").asText("");
            if ("message_stop".equals(type)) {
                return true;
            }
            if ("error".equals(type)) {
                throw new PluginException("LLM 流式错误: "
                        + frame.path("error").path("message").asText("未知错误"));
            }
            if ("message_start".equals(type)) {
                promptTokens[0] = frame.path("message").path("usage").path("input_tokens").asLong(0);
                cacheReadTokens[0] = frame.path("message").path("usage")
                        .path("cache_read_input_tokens").asLong(0);
                return false;
            }
            if ("message_delta".equals(type)) {
                completionTokens[0] = frame.path("usage").path("output_tokens").asLong(completionTokens[0]);
                return false;
            }
            if ("content_block_start".equals(type)) {
                JsonNode block = frame.path("content_block");
                String blockType = block.path("type").asText();
                if ("tool_use".equals(blockType)) {
                    int index = frame.path("index").asInt(0);
                    ids.put(index, block.path("id").asText());
                    names.put(index, block.path("name").asText());
                } else if ("thinking".equals(blockType)) {
                    thinkingBlock[0] = true;
                } else if ("redacted_thinking".equals(blockType)) {
                    // 不可逆加密思考块（安全过滤触发）：无 delta，整块原样保留回传
                    redactedBlockJson[0] = block.toString();
                }
                return false;
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
            return false;
        });

        for (Map.Entry<Integer, String> entry : ids.entrySet()) {
            toolCalls.add(new ToolCallRequest(entry.getValue(),
                    names.getOrDefault(entry.getKey(), ""),
                    arguments.getOrDefault(entry.getKey(), new StringBuilder()).toString()));
        }
        TokenUsage usage = promptTokens[0] == 0 && completionTokens[0] == 0 ? null
                : new TokenUsage(promptTokens[0], completionTokens[0],
                        promptTokens[0] + completionTokens[0], cacheReadTokens[0]);
        String reasoning = null;
        if (redactedBlockJson[0] != null) {
            reasoning = redactedBlockJson[0];
        } else if (thinkingBlock[0] && thinkingText.length() > 0) {
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
}
