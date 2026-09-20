package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.session.Session;
import dev.duo.harness.sessionquery.InvertedSessionIndex;
import dev.duo.harness.sessionquery.SessionQueryService;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话检索端点 HTTP 级测试（M21 工单 08）：命中返回（snippet/事件定位）、
 * session-query 行缺席 503、缺检索词 400。夹具 JSONL 代码生成，零真实会话。
 */
class WebSearchEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private WebFace face;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebSearchEndpointTest —— 会话检索端点：命中/503 降级/400 边界 ===");
    }

    @AfterEach
    void tearDown() {
        if (face != null) {
            face.stop();
        }
    }

    interface ToolsView {

        ToolsService tools();
    }

    private void writeFixture(String id, String text) throws Exception {
        Path sessions = tempDir.resolve("sessions");
        Files.createDirectories(sessions);
        Files.write(sessions.resolve(id + ".jsonl"), List.of(
                "{\"type\":\"user/message\",\"at\":1,\"text\":\"" + text + "\"}"),
                StandardCharsets.UTF_8);
    }

    /** 装配：真实 Context + ToolsPlugin + Web 面（sessionQuery 可选装）。 */
    private WebFace start(SessionQueryService sessionQuery) throws Exception {
        tempDir.resolve("out").toFile().mkdirs();
        Session session = Session.create(tempDir.resolve("sessions"));
        Context ctx = Context.root();
        ctx.plugin(new ToolsPlugin(), null).awaitStartup();
        ChatAgent stub = new ChatAgent() {
            @Override
            public AgentReply send(String userText, AgentListener listener) {
                return new AgentReply("好的", List.of(), true);
            }
        };
        face = WebFace.start(0, ctx, ctx.as(ToolsView.class).tools(), session, stub,
                null, null, tempDir.resolve("out"), 50, null, () -> false, sessionQuery);
        return face;
    }

    private HttpResponse<String> get(WebFace f, String path) throws Exception {
        return client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + f.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void searchReturnsHitsWithSnippet() throws Exception {
        writeFixture("20260919-100000-0001", "苹果的历史讨论");
        writeFixture("20260918-090000-0002", "梨的历史讨论");
        WebFace f = start(new InvertedSessionIndex(tempDir.resolve("sessions")));

        HttpResponse<String> res = get(f, "/api/search?q=" + java.net.URLEncoder.encode("苹果", StandardCharsets.UTF_8));
        assertEquals(200, res.statusCode());
        JsonNode hits = MAPPER.readTree(res.body()).path("hits");
        assertEquals(1, hits.size());
        assertEquals("20260919-100000-0001", hits.get(0).path("sessionId").asText());
        assertTrue(hits.get(0).path("snippet").asText().contains("【苹果】"));
        assertEquals("user/message", hits.get(0).path("eventType").asText());
    }

    @Test
    void serviceAbsentIs503WithNotice() throws Exception {
        WebFace f = start(null);
        HttpResponse<String> res = get(f, "/api/search?q=" + java.net.URLEncoder.encode("苹果", StandardCharsets.UTF_8));
        assertEquals(503, res.statusCode());
        assertTrue(res.body().contains("session-query"));
    }

    @Test
    void missingQueryIs400() throws Exception {
        WebFace f = start(new InvertedSessionIndex(tempDir.resolve("sessions")));
        assertEquals(400, get(f, "/api/search").statusCode());
    }
}
