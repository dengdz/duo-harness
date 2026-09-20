package dev.duo.harness.llm.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.llm.MessageImage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 图片部件序列化（M21 工单 06）：files 投递走 file 引用部件，inline 走 data URI。 */
class FilePartSerializationTest {

    private final MockOpenAiServer server = new MockOpenAiServer();
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private OpenAiCompatAdapter adapter() throws IOException {
        return new OpenAiCompatAdapter(new LlmConfig(server.baseUrl(), "sk-test", "test-model",
                LlmConfig.DEFAULT_SYSTEM_PROMPT));
    }

    @Test
    void fileIdImageSerializesAsFilePart() throws Exception {
        server.respondSse(List.of(MockOpenAiServer.deltaChunk("ok")));
        adapter().stream(new ChatRequest("系统", List.of(ChatMessage.user("看图", List.of(
                new MessageImage("aaaa", "image/png", "file-123"))))), chunk -> { });

        JsonNode parts = json.readTree(server.lastRequestBody())
                .path("messages").get(1).path("content");
        assertEquals("file", parts.get(1).path("type").asText());
        assertEquals("file-123", parts.get(1).path("file").path("file_id").asText());
    }

    @Test
    void inlineImageSerializesAsDataUriPart() throws Exception {
        server.respondSse(List.of(MockOpenAiServer.deltaChunk("ok")));
        adapter().stream(new ChatRequest("系统", List.of(ChatMessage.user("看图", List.of(
                new MessageImage("aaaa", "image/png"))))), chunk -> { });

        JsonNode parts = json.readTree(server.lastRequestBody())
                .path("messages").get(1).path("content");
        assertEquals("image_url", parts.get(1).path("type").asText());
        assertEquals("data:image/png;base64,aaaa", parts.get(1).path("image_url").path("url").asText());
    }
}
