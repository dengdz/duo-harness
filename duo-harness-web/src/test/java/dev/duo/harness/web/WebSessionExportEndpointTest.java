package dev.duo.harness.web;

import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /export 下载流 HTTP 级测试（M21 工单 09）：markdown/json 附件下载
 * （Content-Disposition 命名）、非法格式 400。只导当前会话。
 */
class WebSessionExportEndpointTest {

    @TempDir
    Path tempDir;

    private WebFace face;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebSessionExportEndpointTest —— /export 下载流：附件/400 ===");
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

    private WebFace start() throws Exception {
        tempDir.resolve("out").toFile().mkdirs();
        Session session = Session.create(tempDir.resolve("sessions"));
        session.append(SessionEvent.userMessage("导出内容标记"));
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
        return face;
    }

    private HttpResponse<byte[]> get(WebFace f, String path) throws Exception {
        return client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + f.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void markdownDownloadsAsAttachment() throws Exception {
        WebFace f = start();
        HttpResponse<byte[]> res = get(f, "/api/session/export?format=markdown");
        assertEquals(200, res.statusCode());
        Optional<String> disposition = res.headers().firstValue("Content-Disposition");
        assertTrue(disposition.isPresent() && disposition.get().contains("attachment"));
        assertTrue(disposition.get().contains("duo-session-" + f.currentSession().id() + ".md"));
        String body = new String(res.body(), StandardCharsets.UTF_8);
        assertTrue(body.startsWith("# duo 会话导出"));
        assertTrue(body.contains("导出内容标记"));
    }

    @Test
    void jsonDownloadsAsOriginalCopy() throws Exception {
        WebFace f = start();
        HttpResponse<byte[]> res = get(f, "/api/session/export?format=json");
        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Disposition").orElse("")
                .contains(".jsonl"));
        String body = new String(res.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("导出内容标记")); // 只导当前会话——当前会话的标记在
        assertTrue(body.contains("\"type\":\"user/message\""));
    }

    @Test
    void invalidFormatIs400() throws Exception {
        WebFace f = start();
        HttpResponse<byte[]> res = get(f, "/api/session/export?format=xml");
        assertEquals(400, res.statusCode());
    }
}
