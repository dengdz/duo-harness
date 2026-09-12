package dev.duo.harness.llm.internal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试夹具：内置 HttpServer 模拟 OpenAI 兼容端点（先例：MiniFileSystemServer）。
 * 记录收到的请求体与 Authorization 头；按最近设定的脚本回放——SSE 行序列或错误响应；
 * 支持顺序脚本队列（重试语义测试：错误 → 错误 → 成功）。
 */
final class MockOpenAiServer {

    private final HttpServer server;
    private volatile String lastRequestBody = "";
    private volatile String lastAuthorization = "";
    private volatile Script script = Script.sse(List.of());
    /** 顺序脚本队列（非空时优先消费，耗尽后回退到最近设定的固定脚本）。 */
    private final Queue<Script> scriptQueue = new ConcurrentLinkedQueue<>();
    /** 收到的请求数（重试语义断言用）。 */
    private final AtomicInteger requests = new AtomicInteger();

    private record Script(int statusCode, List<String> ssePayloads, String errorBody) {

        static Script sse(List<String> payloads) {
            return new Script(200, payloads, null);
        }

        static Script error(int statusCode, String body) {
            return new Script(statusCode, List.of(), body);
        }
    }

    MockOpenAiServer() {
        try {
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("测试夹具启动失败：无法绑定 mock 端口", e);
        }
        server.createContext("/chat/completions", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            requests.incrementAndGet();
            Script polled = scriptQueue.poll();
            Script current = polled != null ? polled : script;
            if (current.statusCode() == 200) {
                // SSE 流式：无定长头，写完即关
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (String payload : current.ssePayloads()) {
                        out.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                    }
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } else {
                byte[] body = current.errorBody().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(current.statusCode(), body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
    }

    /** 脚本：成功回放 SSE 行序列（自动追加 data: 前缀与 [DONE] 终止行）。 */
    void respondSse(List<String> dataPayloads) {
        this.script = Script.sse(dataPayloads);
    }

    /** 脚本：非 200 错误响应（JSON 体）。 */
    void respondError(int statusCode, String jsonBody) {
        this.script = Script.error(statusCode, jsonBody);
    }

    /** 顺序脚本：按请求次序逐个消费，耗尽后回退到最近设定的固定脚本。 */
    void respondSequentially(List<Script> scripts) {
        scriptQueue.clear();
        scripts.forEach(scriptQueue::add);
    }

    /** 已收到的请求数（含被重试替换的失败请求）。 */
    int requestCount() {
        return requests.get();
    }

    /** 顺序脚本的错误响应项。 */
    static Script errorScript(int statusCode, String jsonBody) {
        return Script.error(statusCode, jsonBody);
    }

    /** 顺序脚本的成功响应项（SSE 行序列）。 */
    static Script sseScript(List<String> dataPayloads) {
        return Script.sse(dataPayloads);
    }

    /** baseUrl（指向 mock 的 chat/completions）。 */
    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    String lastRequestBody() {
        return lastRequestBody;
    }

    String lastAuthorization() {
        return lastAuthorization;
    }

    void stop() {
        server.stop(0);
    }

    /** 构造一条 OpenAI 流式 chunk 载荷（delta.content 增量），带协议要求的 choices 包裹。 */
    static String deltaChunk(String text) {
        ObjectNode choice = choiceWithDelta();
        choice.putObject("delta").put("content", text);
        return wrapInChoices(choice);
    }

    /** 构造一条 delta.content 为 null 的角色载荷（应被跳过）。 */
    static String roleChunk() {
        ObjectNode choice = choiceWithDelta();
        choice.putObject("delta").put("role", "assistant").putNull("content");
        return wrapInChoices(choice);
    }

    private static ObjectNode choiceWithDelta() {
        ObjectNode choice = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        choice.putObject("delta");
        choice.put("index", 0);
        choice.putNull("finish_reason");
        return choice;
    }

    private static String wrapInChoices(ObjectNode choice) {
        var choices = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(choice);
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .set("choices", choices).toString();
    }
}
