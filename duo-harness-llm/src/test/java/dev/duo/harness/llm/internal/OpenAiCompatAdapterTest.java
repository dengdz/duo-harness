package dev.duo.harness.llm.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OpenAI 兼容适配器用例：mock SSE 端点下验证流式聚合、请求形态与错误呈现。 */
class OpenAiCompatAdapterTest {

    private final ObjectMapper json = new ObjectMapper();
    private final MockOpenAiServer server = new MockOpenAiServer();

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private OpenAiCompatAdapter adapter() throws IOException {
        return new OpenAiCompatAdapter(new LlmConfig(server.baseUrl(), "sk-test", "test-model", LlmConfig.DEFAULT_SYSTEM_PROMPT));
    }

    private List<String> collect(OpenAiCompatAdapter adapter, ChatRequest request) {
        List<String> chunks = new ArrayList<>();
        adapter.stream(request, chunk -> chunks.add(chunk.text()));
        return chunks;
    }

    @Test
    void multiChunksStreamInOrderUntilDone() throws Exception {
        server.respondSse(List.of(
                MockOpenAiServer.deltaChunk("你好"),
                MockOpenAiServer.roleChunk(),
                MockOpenAiServer.deltaChunk("，世界")));

        List<String> chunks = collect(adapter(), new ChatRequest("你是助手", List.of(ChatMessage.user("打招呼"))));

        assertEquals(List.of("你好", "，世界"), chunks, "按序聚合文本增量；delta.content 为 null 的角色 chunk 跳过");
    }

    @Test
    void requestCarriesModelStreamMessagesAndBearer() throws Exception {
        server.respondSse(List.of(MockOpenAiServer.deltaChunk("ok")));

        List<ChatMessage> history = List.of(
                ChatMessage.user("第一问"),
                ChatMessage.assistant("第一答"),
                ChatMessage.user("第二问"));
        List<ToolSpec> toolSpecs = List.of(
                new ToolSpec("read_file", "读取文件", "{\"type\":\"object\"}"));
        collect(adapter(), new ChatRequest("你是助手", history, toolSpecs));

        JsonNode body = json.readTree(server.lastRequestBody());
        assertEquals("test-model", body.path("model").asText());
        assertTrue(body.path("stream").asBoolean(), "应请求流式");
        assertEquals(4, body.path("messages").size(), "system + 三条历史");
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("你是助手", body.path("messages").get(0).path("content").asText());
        assertEquals("user", body.path("messages").get(1).path("role").asText());
        assertEquals("第一问", body.path("messages").get(1).path("content").asText());
        assertEquals("assistant", body.path("messages").get(2).path("role").asText());
        assertEquals("第一答", body.path("messages").get(2).path("content").asText());
        assertEquals("user", body.path("messages").get(3).path("role").asText());
        assertEquals("第二问", body.path("messages").get(3).path("content").asText());
        assertEquals("Bearer sk-test", server.lastAuthorization());
        assertEquals(1, body.path("tools").size(), "tools 清单应序列化");
        assertEquals("function", body.path("tools").get(0).path("type").asText());
        assertEquals("read_file", body.path("tools").get(0).path("function").path("name").asText());
    }

    @Test
    void errorStatusSurfacesStatusCodeAndProviderMessage() throws IOException {
        server.respondError(401, "{\"error\":{\"message\":\"bad api key\"}}");

        PluginException e = assertThrows(PluginException.class,
                () -> collect(adapter(), new ChatRequest("s", List.of(ChatMessage.user("q")))));

        String message = e.getMessage();
        assertTrue(message.contains("401"), message);
        assertTrue(message.contains("bad api key"), "应透出 provider 错误消息: " + message);
    }

    @Test
    void streamTurnAggregatesShardedToolCalls() throws Exception {
        // OpenAI 流式 tool_calls 分片形态：id/name/arguments 按 index 分多帧到达
        String shard1 = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\"\"}}]}}]}";
        String shard2 = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\":\\\"notes.txt\\\"}\"}}]}}]}";
        server.respondSse(List.of(shard1, shard2));

        StringBuilder printed = new StringBuilder();
        LlmTurn turn = adapter().streamTurn(
                new ChatRequest("s", List.of(ChatMessage.user("q")), List.of()), printed::append);

        // 分片聚合：id/name/arguments 按 index 拼回完整调用
        assertEquals(1, turn.toolCalls().size());
        assertEquals("call_1", turn.toolCalls().get(0).id());
        assertEquals("read_file", turn.toolCalls().get(0).name());
        assertEquals("{\"path\":\"notes.txt\"}", turn.toolCalls().get(0).argumentsJson());
        assertTrue(turn.text().isEmpty(), "无文本输出时 text 为空串");
    }

    @Test
    void streamTurnDeliversTextAndEmptyToolCalls() throws Exception {
        server.respondSse(List.of(MockOpenAiServer.deltaChunk("ok")));

        StringBuilder printed = new StringBuilder();
        LlmTurn turn = adapter().streamTurn(
                new ChatRequest("s", List.of(ChatMessage.user("q")), List.of()), printed::append);

        assertEquals("ok", turn.text());
        assertTrue(turn.toolCalls().isEmpty(), "纯文本轮无工具调用");
    }

    @Test
    void streamTurnCapturesReasoningContent() throws Exception {
        // 思考模式（thinking mode）SSE 形态：reasoning_content 增量分多帧与 content 交错到达
        String reasoning1 = "{\"choices\":[{\"delta\":{\"reasoning_content\":\"先看\"}}]}";
        String reasoning2 = "{\"choices\":[{\"delta\":{\"reasoning_content\":\"文件\"}}]}";
        server.respondSse(List.of(reasoning1, reasoning2, MockOpenAiServer.deltaChunk("好的")));

        StringBuilder printed = new StringBuilder();
        LlmTurn turn = adapter().streamTurn(
                new ChatRequest("s", List.of(ChatMessage.user("q")), List.of()), printed::append);

        assertEquals("先看文件", turn.reasoningContent(), "reasoning_content 增量按序聚合");
        assertEquals("好的", turn.text());
        assertEquals("好的", printed.toString(), "textSink 只收正文增量，不含思考内容");
    }

    @Test
    void streamTurnOmitsReasoningWhenAbsent() throws Exception {
        server.respondSse(List.of(MockOpenAiServer.deltaChunk("ok")));

        LlmTurn turn = adapter().streamTurn(
                new ChatRequest("s", List.of(ChatMessage.user("q")), List.of()), s -> { });

        assertEquals(null, turn.reasoningContent(), "非思考模型该字段为 null");
    }

    @Test
    void assistantReasoningContentSerializesToRequest() throws Exception {
        server.respondSse(List.of(MockOpenAiServer.deltaChunk("ok")));

        List<ChatMessage> history = List.of(
                ChatMessage.user("问"),
                ChatMessage.assistantWithToolCalls("", List.of(
                        new dev.duo.harness.llm.ToolCallRequest("call_1", "read_file", "{}")),
                        "需要先读文件"),
                ChatMessage.tool("call_1", "内容"));
        collect(adapter(), new ChatRequest("s", history, List.of()));

        JsonNode body = json.readTree(server.lastRequestBody());
        assertEquals("system", body.path("messages").get(0).path("role").asText(), "system 前置");
        JsonNode user = body.path("messages").get(1);
        JsonNode assistant = body.path("messages").get(2);
        JsonNode tool = body.path("messages").get(3);
        assertEquals("assistant", assistant.path("role").asText());
        assertEquals("需要先读文件", assistant.path("reasoning_content").asText(),
                "思考内容应序列化进 assistant 工具调用消息");
        assertTrue(user.path("reasoning_content").isMissingNode(),
                "user 消息不携带该字段");
        assertTrue(tool.path("reasoning_content").isMissingNode(),
                "tool 消息不携带该字段");
    }

    @Test
    void streamTurnSurfacesNon200Error() throws Exception {
        server.respondError(401, "{\"error\":{\"message\":\"bad key\"}}");

        PluginException e = assertThrows(PluginException.class,
                () -> adapter().streamTurn(
                        new ChatRequest("s", List.of(ChatMessage.user("q"))), s -> { }));

        assertTrue(e.getMessage().contains("401"), e.getMessage());
        assertTrue(e.getMessage().contains("bad key"), e.getMessage());
    }

    @Test
    void nonJsonErrorBodyIsKeptVerbatim() throws IOException {
        server.respondError(502, "bad gateway");

        PluginException e = assertThrows(PluginException.class,
                () -> collect(adapter(), new ChatRequest("s", List.of(ChatMessage.user("q")))));

        assertTrue(e.getMessage().contains("502"), e.getMessage());
        assertTrue(e.getMessage().contains("bad gateway"), e.getMessage());
    }

    @Test
    void networkFailureThrowsPluginException() throws IOException {
        OpenAiCompatAdapter dead = new OpenAiCompatAdapter(
                new LlmConfig("http://localhost:1", "sk", "m", LlmConfig.DEFAULT_SYSTEM_PROMPT));

        PluginException e = assertThrows(PluginException.class,
                () -> dead.stream(new ChatRequest("s", List.of(ChatMessage.user("q"))), chunk -> { }));

        assertTrue(e.getMessage().contains("LLM 调用失败"), e.getMessage());
    }
}
