package dev.duo.harness.attachment;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * files 投递全链（M21 工单 06，ADR-0022 决策 5）：mock Files 端点下——上传换
 * file_id / 本地索引去重（命中不重传）/ 配额满回收最旧自有文件后重试 / 非配额
 * 失败透传 / 失效后重传 / 索引跨实例持久化。
 */
class ImageFileDeliveryTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    /** 请求台账（method path），去重断言用。 */
    private final List<String> requests = new CopyOnWriteArrayList<>();
    /** 每次上传的响应脚本（可编程：正常 / 配额满 / 服务器错误）。 */
    private final List<String[]> uploadResponses = new CopyOnWriteArrayList<>();
    private volatile int uploadCounter;
    private volatile int scriptPointer;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ImageFileDeliveryTest —— files 投递全链：上传/去重/回收/回退 ===");
    }

    @BeforeEach
    void startMock() throws Exception {
        requests.clear();
        uploadResponses.clear();
        uploadCounter = 0;
        scriptPointer = 0;
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/files", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            requests.add(method + " " + path);
            if ("DELETE".equals(method)) {
                respond(exchange, 200, "{}");
                return;
            }
            // POST /files（上传）：按编程脚本回应
            // POST /files（上传）：脚本按序消费（未 scripted 的上传用默认 200）
            int uploadIndex = ++uploadCounter;
            String[] script = scriptPointer < uploadResponses.size()
                    ? uploadResponses.get(scriptPointer++) : new String[]{"200"};
            if ("200".equals(script[0])) {
                respond(exchange, 200, "{\"id\":\"file-" + uploadIndex + "\"}");
            } else {
                respond(exchange, Integer.parseInt(script[0]),
                        "{\"error\":\"" + (script.length > 1 ? script[1] : "error") + "\"}");
            }
        });
        server.start();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        if (status == 400 || status == 500 || status == 429) {
            exchange.sendResponseHeaders(status, body.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        exchange.sendResponseHeaders(status, body.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    @AfterEach
    void stopMock() {
        server.stop(0);
    }

    private FilesApiUploader uploader() {
        return new FilesApiUploader("http://localhost:" + server.getAddress().getPort(),
                "test-key", java.time.Duration.ofSeconds(5));
    }

    private ImageFileDelivery delivery(Path indexFile) {
        return new ImageFileDelivery(uploader(), indexFile);
    }

    @Test
    void uploadReturnsFileIdAndPersistsIndex() throws Exception {
        Path index = tmp.resolve("files-index.json");
        ImageFileDelivery delivery = delivery(index);
        String fileId = delivery.deliver("variant-1", "bytes".getBytes(StandardCharsets.UTF_8),
                "image/png", "shot.png");
        assertEquals("file-1", fileId);
        assertEquals(1, delivery.size());
        assertTrue(Files.readString(index).contains("variant-1")); // 台账已持久化
        assertTrue(requests.stream().anyMatch(r -> r.startsWith("POST /files")));
    }

    @Test
    void sameVariantIdDedupesWithoutRetransmission() throws Exception {
        Path index = tmp.resolve("files-index.json");
        ImageFileDelivery delivery = delivery(index);
        byte[] bytes = "bytes".getBytes(StandardCharsets.UTF_8);
        assertEquals("file-1", delivery.deliver("variant-1", bytes, "image/png", null));
        assertEquals("file-1", delivery.deliver("variant-1", bytes, "image/png", null));
        assertEquals(1L, requests.stream().filter(r -> r.startsWith("POST")).count()); // 命中不重传
    }

    @Test
    void persistedIndexSurvivesAcrossInstances() throws Exception {
        Path index = tmp.resolve("files-index.json");
        assertEquals("file-1", delivery(index).deliver("variant-1",
                "bytes".getBytes(StandardCharsets.UTF_8), "image/png", null));
        long postsAfterFirst = requests.stream().filter(r -> r.startsWith("POST")).count();
        // 新实例（模拟重启）加载同一索引：命中不重传
        ImageFileDelivery restarted = delivery(index);
        assertEquals("file-1", restarted.deliver("variant-1",
                "bytes".getBytes(StandardCharsets.UTF_8), "image/png", null));
        assertEquals((long) postsAfterFirst, requests.stream().filter(r -> r.startsWith("POST")).count());
    }

    @Test
    void quotaFailureEvictsOldestThenRetriesOnce() throws Exception {
        Path index = tmp.resolve("files-index.json");
        ImageFileDelivery delivery = delivery(index);
        assertEquals("file-1", delivery.deliver("variant-a",
                "a".getBytes(StandardCharsets.UTF_8), "image/png", null));
        // 第二次上传遇到配额满：应回收 file-1（DELETE）后重试成功
        uploadResponses.add(new String[]{"400", "file quota exceeded"});
        String fileId = delivery.deliver("variant-b",
                "b".getBytes(StandardCharsets.UTF_8), "image/png", null);
        assertEquals("file-3", fileId); // 脚本 400 占第 2 次，回收后重试为第 3 次
        assertTrue(requests.stream().anyMatch(r -> r.equals("DELETE /files/file-1")),
                "配额满应回收最旧自有文件: " + requests);
        assertEquals(1, delivery.size()); // 台账只剩新文件
    }

    @Test
    void nonQuotaFailurePropagatesWithoutEviction() throws Exception {
        Path index = tmp.resolve("files-index.json");
        ImageFileDelivery delivery = delivery(index);
        assertEquals("file-1", delivery.deliver("variant-a",
                "a".getBytes(StandardCharsets.UTF_8), "image/png", null));
        uploadResponses.add(new String[]{"500", "internal error"});
        assertThrows(FilesApiUploader.FilesApiException.class,
                () -> delivery.deliver("variant-b", "b".getBytes(StandardCharsets.UTF_8),
                        "image/png", null));
        assertTrue(requests.stream().noneMatch(r -> r.startsWith("DELETE"))); // 非配额不回收
    }

    @Test
    void differentVariantIdsBothUpload() throws Exception {
        Path index = tmp.resolve("files-index.json");
        ImageFileDelivery delivery = delivery(index);
        delivery.deliver("variant-1", "bytes".getBytes(StandardCharsets.UTF_8),
                "image/png", null);
        delivery.deliver("variant-2", "other".getBytes(StandardCharsets.UTF_8),
                "image/png", null);
        assertEquals(2L, requests.stream().filter(r -> r.startsWith("POST")).count());
        assertEquals(2, delivery.size()); // 不同 variantId 各记台账
    }
}
