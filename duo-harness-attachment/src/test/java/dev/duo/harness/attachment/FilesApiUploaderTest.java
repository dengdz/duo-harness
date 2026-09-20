package dev.duo.harness.attachment;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FilesApiUploader 单元测试：multipart 构造、file_id 解析、非 2xx 异常。 */
class FilesApiUploaderTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：FilesApiUploaderTest —— Files API 上传（file_id 解析/错误透传） ===");
    }

    @Test
    void 正常上传返回fileId(@TempDir Path tmp) throws Exception {
        // 用内嵌 HttpServer 起 mock 端点（与 MockWebServer 同源思路，但更轻量）
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/files", exchange -> {
            String body = "{\"id\":\"file-abc123\",\"object\":\"file\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes(StandardCharsets.UTF_8).length);
            try (var out = exchange.getResponseBody()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        var uploader = new FilesApiUploader("http://localhost:" + port, "test-key", Duration.ofSeconds(5));
        String fileId = uploader.upload("test".getBytes(StandardCharsets.UTF_8), "image/png", "test.png");

        assertEquals("file-abc123", fileId);
        server.stop(0);
    }

    @Test
    void 非2xx抛异常含状态码(@TempDir Path tmp) throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/files", exchange -> {
            String body = "{\"error\":\"quota exceeded\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, body.getBytes(StandardCharsets.UTF_8).length);
            try (var out = exchange.getResponseBody()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        var uploader = new FilesApiUploader("http://localhost:" + port, "test-key", Duration.ofSeconds(5));
        FilesApiUploader.FilesApiException e = assertThrows(FilesApiUploader.FilesApiException.class,
                () -> uploader.upload("test".getBytes(StandardCharsets.UTF_8), "image/png", "test.png"));
        assertTrue(e.getMessage().contains("429"), "应点名状态码: " + e.getMessage());
        assertTrue(e.getMessage().contains("quota"), "应含服务端原文: " + e.getMessage());
        server.stop(0);
    }
}
