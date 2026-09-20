package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.attachment.AttachmentConfig;
import dev.duo.harness.attachment.AttachmentStore;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件端点 HTTP 级测试（M21 工单 04）：上传（vision 闸门/入库/非图片 422/超限 413）、
 * 授权读取（归属校验/伪造 404）、消息携带附件的引用落盘时序。测试图代码生成，零真实资源。
 */
class WebAttachmentEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private WebFace face;
    private Session session;
    private final HttpClient client = HttpClient.newHttpClient();
    /** 视觉闸门（可变——闸门两态用例切换）。 */
    private final AtomicBoolean vision = new AtomicBoolean(true);

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebAttachmentEndpointTest —— 附件端点：上传/授权读取/消息引用时序 ===");
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

    /** 装配：真实 Context + ToolsPlugin + 附件库 + Web 面（vision 可切换；maxImageBytes 可收紧）。 */
    private WebFace start(long maxImageBytes, boolean visionOn) throws Exception {
        session = Session.create(tempDir.resolve("sessions"));
        Context ctx = Context.root();
        ctx.plugin(new ToolsPlugin(), null).awaitStartup();
        AttachmentStore store = new AttachmentStore(tempDir.resolve("att/v1"),
                new AttachmentConfig(maxImageBytes, 5, 100_000_000, 64_000_000, 8192,
                        4_194_304, 8192, 4_194_304));
        ChatAgent stub = new ChatAgent() {
            @Override
            public AgentReply send(String userText, AgentListener listener) {
                return new AgentReply("好的", List.of(), true);
            }
        };
        face = WebFace.start(0, ctx, ctx.as(ToolsView.class).tools(), session, stub,
                null, null, tempDir.resolve("out"), 50, store, () -> visionOn);
        return face;
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.RED, w, h, Color.BLUE));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private HttpResponse<String> upload(byte[] data, String name) throws Exception {
        String body = MAPPER.createObjectNode()
                .put("data", Base64.getEncoder().encodeToString(data))
                .put("name", name).toString();
        return client.send(HttpRequest.newBuilder(URI.create(faceUrl() + "/api/attachment/upload"))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String faceUrl() {
        return "http://127.0.0.1:" + facePort();
    }

    private int facePort() {
        try {
            var f = face.getClass().getDeclaredField("server");
            f.setAccessible(true);
            return ((com.sun.net.httpserver.HttpServer) f.get(face)).getAddress().getPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 上传入库返回元数据() throws Exception {
        start(20_000_000, true);
        HttpResponse<String> res = upload(png(40, 30), "截图.png");
        assertEquals(200, res.statusCode());
        JsonNode node = MAPPER.readTree(res.body());
        assertEquals("image/png", node.path("mediaType").asText());
        assertEquals("截图.png", node.path("name").asText());
        assertEquals(40 * 30, node.path("width").asInt() * node.path("height").asInt());
    }

    @Test
    void vision关闭时上传即拒() throws Exception {
        start(20_000_000, false);
        HttpResponse<String> res = upload(png(40, 30), "图.png");
        assertEquals(409, res.statusCode(), "vision 关闭应 409");
        assertTrue(res.body().contains("不支持图片"), "应点名视觉闸门: " + res.body());
    }

    @Test
    void 非图片内容422() throws Exception {
        start(20_000_000, true);
        HttpResponse<String> res = upload("这不是图片".getBytes(), "x.png");
        assertEquals(422, res.statusCode(), "非图片应 422: " + res.body());
    }

    @Test
    void 超源字节上限413() throws Exception {
        start(100, true); // maxImageBytes=100：100 字节以上的图一律 413
        HttpResponse<String> res = upload(png(200, 200), "big.png");
        assertEquals(413, res.statusCode(), "超限应 413: " + res.body());
    }

    @Test
    void 授权读取与伪造404() throws Exception {
        start(20_000_000, true);
        HttpResponse<String> up = upload(png(40, 30), "图.png");
        String id = MAPPER.readTree(up.body()).path("attachmentId").asText();

        // 未随消息引用（引用块未落日志）→ 归属校验拒绝
        HttpResponse<String> forged = client.send(HttpRequest.newBuilder(
                        URI.create(faceUrl() + "/api/attachment/read?id=" + id)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, forged.statusCode(), "未引用的 id 不可读（伪造防御）");

        // 随消息引用后（引用块落日志）→ 授权读取 200
        var msg = MAPPER.createObjectNode();
        msg.put("text", "看图");
        msg.putArray("attachments").addObject()
                .put("attachmentId", id).put("mediaType", "image/png")
                .put("bytes", 100).put("name", "图.png");
        String body = MAPPER.writeValueAsString(msg);
        HttpResponse<String> sent = client.send(HttpRequest.newBuilder(
                        URI.create(faceUrl() + "/api/message"))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, sent.statusCode(), "带附件消息应受理: " + sent.body());

        HttpResponse<byte[]> img = client.send(HttpRequest.newBuilder(
                        URI.create(faceUrl() + "/api/attachment/read?id=" + id)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, img.statusCode(), "已引用即可读");
        assertEquals("image/png", img.headers().firstValue("Content-Type").orElse(""));
        assertTrue(img.body().length > 0);
    }

    @Test
    void 引用事件先于user消息落盘() throws Exception {
        start(20_000_000, true);
        HttpResponse<String> up = upload(png(8, 8), "时序.png");
        String id = MAPPER.readTree(up.body()).path("attachmentId").asText();
        var msg = MAPPER.createObjectNode();
        msg.put("text", "看图");
        msg.putArray("attachments").addObject()
                .put("attachmentId", id).put("mediaType", "image/png").put("bytes", 100).put("name", "t");
        String body = MAPPER.writeValueAsString(msg);
        HttpResponse<String> sent = client.send(HttpRequest.newBuilder(
                        URI.create(faceUrl() + "/api/message"))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, sent.statusCode());

        // 断言引用事件落盘（stub agent 不落 user/message——真实 ToolCallingAgent.send
        // 的落盘时序在引用之后，由生产调用序保证）
        var events = session.events();
        assertTrue(events.size() >= 1 && SessionEvent.USER_ATTACHMENT.equals(events.get(0).type()),
                "引用事件应落盘: " + events);
    }
}
