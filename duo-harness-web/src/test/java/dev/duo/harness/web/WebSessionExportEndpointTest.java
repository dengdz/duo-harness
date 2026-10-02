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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /export 下载流 HTTP 级测试（M21 工单 09；M26-07 显式 sessionId 寻址）：
 * markdown/json 附件下载（Content-Disposition 命名）、非法格式/缺参 400、
 * 未知会话 404、未打开的会话按 id 可导。
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
        // sessionsDir 与会话夹具同目录（M26-07 显式寻址按 sessionsDir 解析会话文件）
        face = WebFace.start(0, ctx, ctx.as(ToolsView.class).tools(), session, stub,
                null, null, tempDir.resolve("sessions"), 50, null, () -> false, null);
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
        HttpResponse<byte[]> res = get(f, "/api/session/export?format=markdown&sessionId="
                + f.currentSession().id());
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
        HttpResponse<byte[]> res = get(f, "/api/session/export?format=json&sessionId="
                + f.currentSession().id());
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
        HttpResponse<byte[]> res = get(f, "/api/session/export?format=xml&sessionId="
                + f.currentSession().id());
        assertEquals(400, res.statusCode());
    }

    @Test
    void missingSessionIdIs400() throws Exception {
        // M26-07 显式寻址：缺 sessionId = 匿名导出不再受理（确定性——不猜你要哪个）
        WebFace f = start();
        HttpResponse<byte[]> res = get(f, "/api/session/export?format=markdown");
        assertEquals(400, res.statusCode());
    }

    @Test
    void unknownSessionIs404() throws Exception {
        WebFace f = start();
        HttpResponse<byte[]> res = get(f, "/api/session/export?format=markdown&sessionId=20260101-000000-0000");
        assertEquals(404, res.statusCode(), "合法形态的不存在 id → 404（zzz 形态会被白名单 400 拒）");
    }

    @Test
    void unopenedSessionExportableById() throws Exception {
        // M26-07 新能力：未打开的会话按 id 直接导出（临时加载、导出后释放）——
        // 不再需要先 /switch 打开；含交付声明事件则交付清单章随导出呈现
        Path sessions = tempDir.resolve("sessions");
        Files.createDirectories(sessions);
        Path second = sessions.resolve("20260926-220000-0002.jsonl");
        Files.write(second, List.of(
                "{\"type\":\"session\",\"version\":1,\"cwd\":\"" + tempDir + "\"}",
                "{\"type\":\"user/message\",\"at\":1,\"text\":\"第二个会话的标记词风筝\"}",
                "{\"type\":\"deliverable/presented\",\"at\":2,\"text\":\"[\\\"/tmp/p/风筝报告.md\\\"]\"}"),
                StandardCharsets.UTF_8);
        WebFace f = start();

        HttpResponse<byte[]> res = get(f, "/api/session/export?format=markdown&sessionId=20260926-220000-0002");
        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Disposition").orElse("")
                .contains("duo-session-20260926-220000-0002.md"), "按 id 命名导出");
        String body = new String(res.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("风筝"), "未打开的会话内容可导出");
        assertTrue(body.contains("## 交付清单（模型声明）"), "交付声明随导出呈现");
        assertTrue(body.contains("风筝报告.md"));
        // 导出后释放锁：会话可再被打开（无锁残留）——释放发生在响应流写完后的 finally，
        // 客户端断言与服端 close 存在微小竞态窗口（CI 慢 runner 显形，M34 发版实测）：
        // 锁重试等待直至释放，不竞速
        Session reopen = null;
        for (int i = 0; i < 50 && reopen == null; i++) {
            try {
                reopen = Session.load(second);
            } catch (dev.duo.harness.session.SessionLockedException e) {
                Thread.sleep(100); // 服端 finally 尚未走到：等释放后重试
            }
        }
        assertEquals(2, reopen.events().size());
        reopen.close();
    }
}
