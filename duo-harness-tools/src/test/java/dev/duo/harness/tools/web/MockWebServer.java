package dev.duo.harness.tools.web;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试夹具：内置 HttpServer 模拟任意外网端点（先例：MockOpenAiServer）。
 * 记录最近一次请求的方法/路径/请求头；按脚本回放——状态码、响应头、响应体、
 * 响应前延迟（超时测试）。单 context 兜全部路径，重定向链多跳共用同一服务器。
 */
final class MockWebServer {

    /** 一次响应脚本：状态码 + 响应头 + 响应体 + 发送前延迟毫秒。 */
    record Script(int status, Map<String, String> headers, byte[] body, long delayMs) {

        static Script ok(String contentType, String body) {
            return new Script(200, Map.of("Content-Type", contentType),
                    body.getBytes(StandardCharsets.UTF_8), 0);
        }

        static Script status(int status, Map<String, String> headers, String body) {
            return new Script(status, headers, body.getBytes(StandardCharsets.UTF_8), 0);
        }
    }

    private final HttpServer server;
    private final AtomicReference<Script> script = new AtomicReference<>(Script.ok("text/html", "<p>ok</p>"));
    /** 顺序脚本队列（非空时优先消费，耗尽后回退到最近设定的固定脚本）——重定向链用。 */
    private final Queue<Script> scriptQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>("");
    private final AtomicReference<String> lastPath = new AtomicReference<>("");
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicInteger requests = new AtomicInteger();

    MockWebServer() {
        try {
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("测试夹具启动失败：无法绑定 mock 端口", e);
        }
        server.createContext("/", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().getPath());
            exchange.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(java.util.Locale.ROOT), v.getFirst()));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.incrementAndGet();
            Script polled = scriptQueue.poll();
            Script current = polled != null ? polled : script.get();
            if (current.delayMs() > 0) {
                try {
                    Thread.sleep(current.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = current.body() == null ? new byte[0] : current.body();
            current.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(current.status(), body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (body.length > 0) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
    }

    /** 设定固定响应脚本（清空顺序队列——防止上一用例的排队脚本泄漏到本用例）。 */
    void respond(Script script) {
        scriptQueue.clear();
        this.script.set(script);
    }

    /** 设定顺序脚本（逐请求消费，耗尽后回退固定脚本）——重定向链用。 */
    void respondInOrder(Script... scripts) {
        scriptQueue.addAll(java.util.Arrays.asList(scripts));
    }

    /** 基地址（http://localhost:随机端口）。 */
    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    String lastHeader(String name) {
        return lastHeaders.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    String lastMethod() {
        return lastMethod.get();
    }

    String lastPath() {
        return lastPath.get();
    }

    String lastBody() {
        return lastBody.get();
    }

    int requestCount() {
        return requests.get();
    }

    void stop() {
        server.stop(0);
    }
}
