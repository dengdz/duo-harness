package dev.duo.harness.llm.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.ChatRequest;
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

        List<String> chunks = collect(adapter(), new ChatRequest("你是助手", "打招呼"));

        assertEquals(List.of("你好", "，世界"), chunks, "按序聚合文本增量；delta.content 为 null 的角色 chunk 跳过");
    }

    @Test
    void requestCarriesModelStreamMessagesAndBearer() throws Exception {
        server.respondSse(List.of(MockOpenAiServer.deltaChunk("ok")));

        collect(adapter(), new ChatRequest("你是助手", "问题"));

        JsonNode body = json.readTree(server.lastRequestBody());
        assertEquals("test-model", body.path("model").asText());
        assertTrue(body.path("stream").asBoolean(), "应请求流式");
        assertEquals(2, body.path("messages").size(), "system + user 两条消息");
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("你是助手", body.path("messages").get(0).path("content").asText());
        assertEquals("user", body.path("messages").get(1).path("role").asText());
        assertEquals("问题", body.path("messages").get(1).path("content").asText());
        assertEquals("Bearer sk-test", server.lastAuthorization());
    }

    @Test
    void errorStatusSurfacesStatusCodeAndProviderMessage() throws IOException {
        server.respondError(401, "{\"error\":{\"message\":\"bad api key\"}}");

        PluginException e = assertThrows(PluginException.class,
                () -> collect(adapter(), new ChatRequest("s", "q")));

        String message = e.getMessage();
        assertTrue(message.contains("401"), message);
        assertTrue(message.contains("bad api key"), "应透出 provider 错误消息: " + message);
    }

    @Test
    void nonJsonErrorBodyIsKeptVerbatim() throws IOException {
        server.respondError(502, "bad gateway");

        PluginException e = assertThrows(PluginException.class,
                () -> collect(adapter(), new ChatRequest("s", "q")));

        assertTrue(e.getMessage().contains("502"), e.getMessage());
        assertTrue(e.getMessage().contains("bad gateway"), e.getMessage());
    }

    @Test
    void networkFailureThrowsPluginException() throws IOException {
        OpenAiCompatAdapter dead = new OpenAiCompatAdapter(
                new LlmConfig("http://localhost:1", "sk", "m", LlmConfig.DEFAULT_SYSTEM_PROMPT));

        PluginException e = assertThrows(PluginException.class,
                () -> dead.stream(new ChatRequest("s", "q"), chunk -> { }));

        assertTrue(e.getMessage().contains("LLM 调用失败"), e.getMessage());
    }
}
