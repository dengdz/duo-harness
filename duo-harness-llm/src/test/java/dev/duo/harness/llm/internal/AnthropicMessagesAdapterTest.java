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
                + "SSE 聚合、usage 双帧、错误呈现（7 用例） ===");
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
                LlmConfig.DELIVERY_INLINE, LlmConfig.PROVIDER_ANTHROPIC));
    }

    @Test
    void requestCarriesAuthHeadersAndSystem() throws Exception {
        server.respondSse(List.of(MockAnthropicServer.messageStop()));
        adapter().streamTurn(request(), text -> { });

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
}
