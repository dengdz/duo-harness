package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.fileref.FileReferenceService;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.ToolsPlugin;
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
 * @file 补全端点 HTTP 级测试（M21 工单 07）：候选返回（前缀/路径段）、无 workspace
 * 装配 503。夹具 @TempDir 代码生成。
 */
class WebFileCompleteEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private WebFace face;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebFileCompleteEndpointTest —— @ 补全端点：候选/503 降级 ===");
    }

    @AfterEach
    void tearDown() {
        if (face != null) {
            face.stop();
        }
    }

    interface ToolsView {

        dev.duo.harness.tools.ToolsService tools();
    }

    private void writeFixture() throws Exception {
        Files.write(tempDir.resolve("README.md"), List.of("x"), StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("src/main"));
        Files.write(tempDir.resolve("src/Main.java"), List.of("x"), StandardCharsets.UTF_8);
    }

    private WebFace start(FileReferenceService fileRefs) throws Exception {
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
                null, null, tempDir.resolve("out"), 50, null, () -> false, null);
        // 补全服务经装配器直传（与 WebPlugin 同路径；不走服务声明）
        face.setFileRefs(fileRefs);
        return face;
    }

    private HttpResponse<String> get(WebFace f, String path) throws Exception {
        return client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + f.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void completeReturnsCandidates() throws Exception {
        writeFixture();
        WebFace f = start(new FileReferenceService(tempDir));
        HttpResponse<String> res = get(f, "/api/file-complete?q="
                + java.net.URLEncoder.encode("src/ma", StandardCharsets.UTF_8));
        assertEquals(200, res.statusCode());
        // 大小写不敏感前缀：src/main（目录）与 src/Main.java 同时命中
        JsonNode suggestions = MAPPER.readTree(res.body()).path("suggestions");
        assertEquals(2, suggestions.size());
        assertTrue(MAPPER.readTree(res.body()).toString().contains("src/main"));
    }

    @Test
    void emptyTokenReturnsTopLevelEntries() throws Exception {
        writeFixture();
        WebFace f = start(new FileReferenceService(tempDir));
        HttpResponse<String> res = get(f, "/api/file-complete?q=");
        assertEquals(200, res.statusCode());
        assertTrue(MAPPER.readTree(res.body()).path("suggestions").size() >= 2);
    }

    @Test
    void serviceAbsentIs503() throws Exception {
        WebFace f = start(null);
        assertEquals(503, get(f, "/api/file-complete?q=readme").statusCode());
    }
}
