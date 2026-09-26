package dev.duo.harness.llm.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.llm.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anthropic-messages 适配器用例（M24 工单 08，ADR-0026 决策七）：请求映射
 * （system 单列 / x-api-key + anthropic-version / tool_use 块 / 连续 TOOL 合并为
 * 单条 user 的 tool_result）、SSE 聚合（text_delta / tool_use 分片 / usage 双帧）、
 * 错误呈现。
 */
class AnthropicMessagesAdapterTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：AnthropicMessagesAdapterTest —— Anthropic-messages 适配器：请求映射、"
                + "SSE 聚合、usage 双帧、错误呈现（16 用例） ===");
    }

    private final ObjectMapper json = new ObjectMapper();
    private final MockAnthropicServer server = new MockAnthropicServer();

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private AnthropicMessagesAdapter adapter() {
        return new AnthropicMessagesAdapter(new LlmConfig(server.baseUrl(), "sk-ant-test",
                "claude-test", LlmConfig.DEFAULT_SYSTEM_PROMPT, 1, 0, 90_000, false,
                LlmConfig.DELIVERY_INLINE, LlmConfig.PROVIDER_ANTHROPIC, List.of()));
    }

    @Test
    void requestCarriesAuthHeadersAndSystem() throws Exception {
        // off 档承载「缺省请求形态」断言（thinking 形态归 effort 专测用例——工单 10 缺省档改 medium）
        server.respondSse(List.of(MockAnthropicServer.messageStop()));
        new AnthropicMessagesAdapter(configWithEffort(LlmConfig.EFFORT_OFF))
                .streamTurn(request(), text -> { });

        JsonNode body = json.readTree(server.lastRequestBody());
        assertEquals("sk-ant-test", server.lastApiKey(), "x-api-key 鉴权头");
        assertEquals("2023-06-01", server.lastVersion(), "anthropic-version 头");
        assertEquals("claude-test", body.path("model").asText());
        assertEquals(8192, body.path("max_tokens").asInt(), "协议必填 max_tokens 缺省");
        assertTrue(body.path("stream").asBoolean());
        assertEquals("系统提示", body.path("system").asText(), "system 单列（不进 messages）");
    }

    @Test
    void toolUseAssistantAndToolResultMergeIntoAlternatingRoles() throws Exception {
        // assistant 发起两个工具调用 + 两条 TOOL 结果回填 → tool_use 块数组 + 连续 TOOL
        // 合并为单条 user 消息（多 tool_result 块）——协议要求 user/assistant 交替
        server.respondSse(List.of(MockAnthropicServer.messageStop()));
        List<ChatMessage> history = List.of(
                ChatMessage.user("查一下"),
                ChatMessage.assistantWithToolCalls("", List.of(
                        new ToolCallRequest("call-1", "read", "{\"path\":\"a.txt\"}"),
                        new ToolCallRequest("call-2", "glob", "{\"pattern\":\"*.md\"}")), null),
                ChatMessage.tool("call-1", "内容A"),
                ChatMessage.tool("call-2", "内容B"),
                ChatMessage.user("继续"));
        adapter().streamTurn(new ChatRequest("系统提示", history), text -> { });

        JsonNode messages = json.readTree(server.lastRequestBody()).path("messages");
        assertEquals(4, messages.size(), "user / assistant(tool_use) / user(tool_result×2) / user");
        assertEquals("user", messages.get(0).path("role").asText());

        JsonNode assistant = messages.get(1);
        assertEquals("assistant", assistant.path("role").asText());
        JsonNode blocks = assistant.path("content");
        assertEquals("tool_use", blocks.get(0).path("type").asText());
        assertEquals("call-1", blocks.get(0).path("id").asText());
        assertEquals("read", blocks.get(0).path("name").asText());
        assertEquals("a.txt", blocks.get(0).path("input").path("path").asText(), "argumentsJson 解析为对象");
        assertEquals("call-2", blocks.get(1).path("id").asText());

        JsonNode merged = messages.get(2);
        assertEquals("user", merged.path("role").asText(), "连续 TOOL 合并为单条 user");
        assertEquals(2, merged.path("content").size(), "两条 tool_result 块");
        assertEquals("tool_result", merged.path("content").get(0).path("type").asText());
        assertEquals("call-1", merged.path("content").get(0).path("tool_use_id").asText());
        assertEquals("内容A", merged.path("content").get(0).path("content").asText());
    }

    @Test
    void toolsSerializeAsInputSchema() throws Exception {
        server.respondSse(List.of(MockAnthropicServer.messageStop()));
        adapter().streamTurn(new ChatRequest("系统提示", List.of(ChatMessage.user("hi")),
                List.of(new ToolSpec("read", "读文件", "{\"type\":\"object\",\"properties\":{}}"))),
                text -> { });

        JsonNode tools = json.readTree(server.lastRequestBody()).path("tools");
        assertEquals("read", tools.get(0).path("name").asText());
        assertEquals("object", tools.get(0).path("input_schema").path("type").asText(),
                "parametersJson → input_schema");
    }

    @Test
    void sseAggregatesTextToolCallsAndDualFrameUsage() throws Exception {
        server.respondSse(List.of(
                MockAnthropicServer.messageStart(120),
                MockAnthropicServer.textDelta("你好"),
                MockAnthropicServer.textDelta("，世界"),
                MockAnthropicServer.toolUseStart(1, "call-9", "read"),
                MockAnthropicServer.inputJsonDelta(1, "{\"path\":"),
                MockAnthropicServer.inputJsonDelta(1, "\"b.txt\"}"),
                MockAnthropicServer.messageDelta(34),
                MockAnthropicServer.messageStop()));
        StringBuilder sink = new StringBuilder();
        LlmTurn turn = adapter().streamTurn(request(), sink::append);

        assertEquals("你好，世界", turn.text());
        assertEquals(1, turn.toolCalls().size());
        ToolCallRequest call = turn.toolCalls().get(0);
        assertEquals("call-9", call.id());
        assertEquals("read", call.name());
        assertEquals("{\"path\":\"b.txt\"}", call.argumentsJson(), "input_json_delta 分片聚合");
        assertEquals(120, turn.usage().promptTokens(), "input_tokens 来自 message_start");
        assertEquals(34, turn.usage().completionTokens(), "output_tokens 来自 message_delta");
        assertEquals(154, turn.usage().totalTokens());
        assertEquals("你好，世界", sink.toString(), "文本增量经 sink 回调");
    }

    @Test
    void streamDeliversTextChunksOnly() {
        server.respondSse(List.of(
                MockAnthropicServer.messageStart(10),
                MockAnthropicServer.textDelta("片段一"),
                MockAnthropicServer.textDelta("片段二"),
                MockAnthropicServer.messageStop()));
        List<String> chunks = new ArrayList<>();
        adapter().stream(request(), chunk -> chunks.add(chunk.text()));
        assertEquals(List.of("片段一", "片段二"), chunks);
    }

    @Test
    void nonSuccessResponseSurfacesProviderMessage() {
        server.respondError(401,
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
        PluginException exception = assertThrows(PluginException.class,
                () -> adapter().streamTurn(request(), text -> { }));
        assertTrue(exception.getMessage().contains("HTTP 401"), exception.getMessage());
        assertTrue(exception.getMessage().contains("invalid x-api-key"), exception.getMessage());
    }

    @Test
    void streamEndsWithoutStopFrameWhenServerCloses() {
        // 服务端先关流（无 message_stop 帧）：读取到流尾即正常收口，不挂死
        server.respondSse(List.of(MockAnthropicServer.textDelta("ok")));
        LlmTurn result = adapter().streamTurn(request(), text -> { });
        assertEquals("ok", result.text());
        assertNull(result.usage(), "无 usage 帧时为 null");
    }

    @Test
    void errorFrameSurfacesAsExceptionNotSilentTruncation() {
        // SSE error 帧（M24 工单 03 行级轴同类发现的 Anthropic 面预防）：转异常上报，不静默截断
        ObjectNode errorFrame = JsonNodeFactory.instance.objectNode();
        errorFrame.put("type", "error");
        errorFrame.putObject("error").put("message", "overloaded_error");
        server.respondSse(List.of(
                MockAnthropicServer.messageStart(10),
                errorFrame.toString(),
                MockAnthropicServer.textDelta("不应到达")));
        PluginException exception = assertThrows(PluginException.class,
                () -> adapter().streamTurn(request(), text -> { }));
        assertTrue(exception.getMessage().contains("overloaded_error"), exception.getMessage());
    }

    private ChatRequest request() {
        return new ChatRequest("系统提示", List.of(ChatMessage.user("hi")), List.of());
    }

    @Test
    void cacheControl三级断点打在system块与末条消息() throws Exception {
        // M25 工单 06：system 按三级划分组块（身份前缀/稳定身份各打 ephemeral），
        // 最后一条消息追加动态段断点；单段 system（无空行边界）退字符串形态
        server.respondSse(List.of(MockAnthropicServer.messageStop()));
        adapter().streamTurn(new ChatRequest(
                "你是固定助手。\n\n## AGENTS.md 约定\n静态片段正文",
                List.of(ChatMessage.user("hi"), ChatMessage.assistant("ok"),
                        ChatMessage.user("go")), List.of()), text -> { });

        JsonNode body = json.readTree(server.lastRequestBody());
        JsonNode system = body.get("system");
        assertTrue(system != null && system.isArray(), "多段 system 组块为数组: " + system);
        assertEquals(2, system.size(), "身份前缀 + 稳定身份两块");
        assertEquals("你是固定助手。", system.get(0).path("text").asText());
        assertEquals("ephemeral", system.get(0).path("cache_control").path("type").asText(),
                "断点 1：身份前缀");
        // 块间 \n\n 还原：两块拼接 = 原 system 逐字节等价（缓存键不受组块影响）
        // 分隔符并入 body 块头部：块[0] + 块[1] = 原 system 逐字节等价
        assertEquals(system.get(0).path("text").asText()
                + system.get(1).path("text").asText(),
                "你是固定助手。\n\n## AGENTS.md 约定\n静态片段正文", "两块拼接逐字节还原");
        assertEquals("ephemeral", system.get(1).path("cache_control").path("type").asText(),
                "断点 2：稳定身份");

        JsonNode messages = body.get("messages");
        JsonNode last = messages.get(messages.size() - 1);
        assertEquals("ephemeral",
                last.path("content").get(last.path("content").size() - 1)
                        .path("cache_control").path("type").asText(),
                "断点 3：动态段（末条消息的 content 块）");
        assertTrue(last.path("content").isArray(), "字符串 content 组块后挂断点");
        for (JsonNode block : last.path("content")) {
            assertEquals("text", block.path("type").asText(),
                    "content 块必须带判别字段 type（BUG-20260926-01）");
        }
        // 非末条不打断点（中间消息保持干净——content 仍为字符串）
        assertTrue(messages.get(0).path("content").isTextual(), "中间消息 content 不动");
    }

    @Test
    void cacheControl单段system退字符串兼容() throws Exception {
        server.respondSse(List.of(MockAnthropicServer.messageStop()));
        adapter().streamTurn(request(), text -> { });

        JsonNode body = json.readTree(server.lastRequestBody());
        // "系统提示" 无空行边界 → 单块无断点 → 字符串形态（旧路径兼容）
        assertTrue(body.get("system").isTextual(), "单段保持字符串: " + body.get("system"));
        JsonNode messages = body.get("messages");
        JsonNode lastContent = messages.get(messages.size() - 1).path("content");
        assertEquals("ephemeral",
                lastContent.get(lastContent.size() - 1).path("cache_control").path("type").asText(),
                "动态段断点恒打（末条消息 content 块）");
    }

    @Test
    void 缓存计数跨轮不泄漏() throws Exception {
        // 行级审查阻断修复回归：第 1 轮命中 800 → 第 2 轮 provider 未报缓存 → 0
        server.respondSse(List.of(
                MockAnthropicServer.messageStart(1000, 800),
                MockAnthropicServer.messageStop()));
        adapter().streamTurn(request(), text -> { });
        server.respondSse(List.of(
                MockAnthropicServer.messageStart(900),
                MockAnthropicServer.messageStop()));
        LlmTurn second = adapter().streamTurn(request(), text -> { });
        assertEquals(0, second.usage().cachedTokens(), "第二轮无缓存报告即 0（不残留 800）");
    }

    @Test
    void usage透出缓存命中token() throws Exception {
        // M25 工单 06：cache_read_input_tokens 进 TokenUsage.cachedTokens
        server.respondSse(List.of(
                MockAnthropicServer.messageStart(1000, 800),
                MockAnthropicServer.messageStop()));
        LlmTurn turn = adapter().streamTurn(request(), text -> { });
        assertEquals(800, turn.usage().cachedTokens(), "缓存命中 800 进用量");
        assertEquals(1000, turn.usage().promptTokens());
    }

    @Test
    void effortOffOmitsThinkingAndKeepsDefaultMaxTokens() throws Exception {
        // 工单 10 Anthropic 行：off → 不带 thinking 节点，max_tokens 保持协议缺省
        server.respondSse(List.of(MockAnthropicServer.messageStop()));
        AnthropicMessagesAdapter offAdapter = new AnthropicMessagesAdapter(configWithEffort(LlmConfig.EFFORT_OFF));
        offAdapter.streamTurn(request(), text -> { });

        JsonNode body = json.readTree(server.lastRequestBody());
        assertNull(body.get("thinking"), "off 档不应携带 thinking 节点");
        assertEquals(8192, body.get("max_tokens").asInt(), "off 档 max_tokens 为协议缺省");
    }

    @Test
    void effortLevelsCarryThinkingBudgetAndRaiseMaxTokens() throws Exception {
        // 工单 10 Anthropic 行：low/medium/high → thinking enabled + budget_tokens 档位值，
        // max_tokens 抬到 budget + 1024（协议要求 max_tokens > budget_tokens）
        record Level(String level, int budget) { }
        List<Level> levels = List.of(
                new Level(LlmConfig.EFFORT_LOW, LlmConfig.ANTHROPIC_BUDGET_LOW),
                new Level(LlmConfig.EFFORT_MEDIUM, LlmConfig.ANTHROPIC_BUDGET_MEDIUM),
                new Level(LlmConfig.EFFORT_HIGH, LlmConfig.ANTHROPIC_BUDGET_HIGH));
        for (Level lvl : levels) {
            server.respondSse(List.of(MockAnthropicServer.messageStop()));
            AnthropicMessagesAdapter onAdapter = new AnthropicMessagesAdapter(configWithEffort(lvl.level()));
            onAdapter.streamTurn(request(), text -> { });

            JsonNode body = json.readTree(server.lastRequestBody());
            assertEquals("enabled", body.path("thinking").get("type").asText(), lvl.level() + " 应开启 thinking");
            assertEquals(lvl.budget(), body.path("thinking").get("budget_tokens").asInt(),
                    lvl.level() + " budget_tokens 档位值");
            assertTrue(body.get("max_tokens").asInt() > lvl.budget(),
                    lvl.level() + " max_tokens 必须大于 budget_tokens");
        }
    }

    @Test
    void requestEffortOverrideBeatsConfigEffort() throws Exception {
        // 工单 10 辅助降档：请求级覆盖优先于配置档——config=high 请求 override=low → budget 2048
        server.respondSse(List.of(MockAnthropicServer.messageStop()));
        AnthropicMessagesAdapter highAdapter = new AnthropicMessagesAdapter(configWithEffort(LlmConfig.EFFORT_HIGH));
        ChatRequest lowRequest = new ChatRequest("系统提示",
                List.of(new ChatMessage(ChatMessage.Role.USER, "问", null, null, null)),
                List.of(), LlmConfig.EFFORT_LOW);
        highAdapter.streamTurn(lowRequest, text -> { });

        JsonNode body = json.readTree(server.lastRequestBody());
        assertEquals(LlmConfig.ANTHROPIC_BUDGET_LOW,
                body.path("thinking").get("budget_tokens").asInt(),
                "请求级覆盖压过配置档（辅助性请求强制 low）");
    }

    /** 带 effort 档的配置（11 参兼容构造 + withEffort 链式）。 */
    private LlmConfig configWithEffort(String effort) {
        return new LlmConfig(server.baseUrl(), "sk-ant-test",
                "claude-test", LlmConfig.DEFAULT_SYSTEM_PROMPT, 1, 0, 90_000, false,
                LlmConfig.DELIVERY_INLINE, LlmConfig.PROVIDER_ANTHROPIC, List.of())
                .withEffort(effort);
    }

    @Test
    void thinkingBlocksAreCapturedIntoReasoningChannel() throws Exception {
        // 工单 10 审查修复（行级 high）：思考档响应的 thinking 块（thinking_delta +
        // signature_delta）聚合为块 JSON 落 reasoningContent——工具循环下一轮回传的载体
        server.respondSse(List.of(
                "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"推\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig-1\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\"}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"答\"}}",
                MockAnthropicServer.messageStop()));
        StringBuilder text = new StringBuilder();
        LlmTurn turn = adapter().streamTurn(request(), text::append);

        assertEquals("答", text.toString());
        JsonNode block = json.readTree(turn.reasoningContent());
        assertEquals("thinking", block.path("type").asText());
        assertEquals("推", block.path("thinking").asText());
        assertEquals("sig-1", block.path("signature").asText(), "signature 必须随块保留（回传校验依据）");
    }

    @Test
    void assistantHistoryReplaysThinkingBlockInContentArray() throws Exception {
        // 工单 10 审查修复：assistant 历史消息（携块 JSON 形态 reasoningContent）在下一轮
        // 请求中把 thinking 块插在 content 数组首位（扩展思考协议的签名回传要求）
        server.respondSse(List.of(MockAnthropicServer.messageStop()));
        String blockJson = "{\"type\":\"thinking\",\"thinking\":\"推\",\"signature\":\"sig-1\"}";
        ChatMessage assistantWithThinking = ChatMessage.assistantWithToolCalls(
                "答", List.of(new ToolCallRequest("t1", "read_file", "{}")),
                blockJson);
        ChatRequest history = new ChatRequest("系统提示", List.of(
                ChatMessage.user("问"), assistantWithThinking,
                ChatMessage.tool("t1", "结果")));
        adapter().streamTurn(history, text -> { });

        JsonNode body = json.readTree(server.lastRequestBody());
        JsonNode assistant = body.path("messages").get(1);
        JsonNode firstBlock = assistant.path("content").get(0);
        assertEquals("thinking", firstBlock.path("type").asText(), "thinking 块应插在 content 数组首位");
        assertEquals("sig-1", firstBlock.path("signature").asText());
        assertEquals("答", assistant.path("content").get(1).path("text").asText(), "text 块紧随其后");
    }
}
