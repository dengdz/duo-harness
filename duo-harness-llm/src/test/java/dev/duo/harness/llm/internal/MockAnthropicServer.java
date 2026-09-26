package dev.duo.harness.llm.internal;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 测试夹具：内置 HttpServer 模拟 Anthropic messages 端点（夹具先例 MockOpenAiServer）。
 * 记录收到的请求体与 x-api-key / anthropic-version 头；按最近脚本回放 SSE 帧序列或错误响应。
 */
final class MockAnthropicServer {

    private final HttpServer server;
    private volatile String lastRequestBody = "";
    private volatile String lastApiKey = "";
    private volatile String lastVersion = "";
    private volatile Script script = Script.sse(List.of());

    private record Script(int statusCode, List<String> ssePayloads, String errorBody) {

        static Script sse(List<String> payloads) {
            return new Script(200, payloads, null);
        }

        static Script error(int statusCode, String body) {
            return new Script(statusCode, List.of(), body);
        }
    }

    MockAnthropicServer() {
        try {
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("测试夹具启动失败：无法绑定 mock 端口", e);
        }
        server.createContext("/v1/messages", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastApiKey = exchange.getRequestHeaders().getFirst("x-api-key");
            lastVersion = exchange.getRequestHeaders().getFirst("anthropic-version");
            Script current = script;
            if (current.statusCode() == 200) {
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (String payload : current.ssePayloads()) {
                        out.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                    }
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

    /** 脚本：成功回放 SSE 帧序列（Anthropic 以 message_stop 结束，无 [DONE]）。 */
    void respondSse(List<String> dataPayloads) {
        this.script = Script.sse(dataPayloads);
    }

    /** 脚本：非 200 错误响应（JSON 体）。 */
    void respondError(int statusCode, String jsonBody) {
        this.script = Script.error(statusCode, jsonBody);
    }

    /** baseUrl（指向 mock 的 /v1/messages）。 */
    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /**
     * 最近请求体（BUG-20260926-01 结构化防线）：附带最小协议校验——messages/system
     * 的 content 数组每块必须携带判别字段 {@code type}，违约在此点名（mock 不再是
     * 纯回显器，组块合法性在测试内暴露——真机 422 的离线等价物）。
     */
    String lastRequestBody() {
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(lastRequestBody);
            java.util.List<com.fasterxml.jackson.databind.JsonNode> contentHolders =
                    new java.util.ArrayList<>();
            root.path("messages").forEach(contentHolders::add);
            if (root.path("system").isArray()) {
                root.path("system").forEach(contentHolders::add);
            }
            for (var holder : contentHolders) {
                var content = holder.path("content");
                if (content.isArray()) {
                    for (var block : content) {
                        if (block.path("type").isMissingNode()) {
                            throw new IllegalStateException("mock 协议校验失败：content 块缺判别字段 type: "
                                    + block);
                        }
                    }
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("mock 协议校验失败：请求体不是合法 JSON", e);
        }
        return lastRequestBody;
    }

    String lastApiKey() {
        return lastApiKey;
    }

    String lastVersion() {
        return lastVersion;
    }

    void stop() {
        server.stop(0);
    }

    /** 构造 message_start 帧（input_tokens）。 */
    static String messageStart(long inputTokens) {
        return messageStart(inputTokens, 0);
    }

    /** 构造 message_start 帧（input_tokens + 缓存命中，M25 工单 06 用例）。 */
    static String messageStart(long inputTokens, long cacheReadTokens) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("type", "message_start");
        var usage = root.putObject("message").putObject("usage");
        usage.put("input_tokens", inputTokens);
        if (cacheReadTokens > 0) {
            usage.put("cache_read_input_tokens", cacheReadTokens);
        }
        return root.toString();
    }

    /** 构造文本增量帧（content_block_delta / text_delta）。 */
    static String textDelta(String text) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("type", "content_block_delta");
        root.put("index", 0);
        root.putObject("delta").put("type", "text_delta").put("text", text);
        return root.toString();
    }

    /** 构造 tool_use 块起始帧（id/name 在 start 携带，参数走 input_json_delta 分片）。 */
    static String toolUseStart(int index, String id, String name) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("type", "content_block_start");
        root.put("index", index);
        root.putObject("content_block")
                .put("type", "tool_use")
                .put("id", id)
                .put("name", name);
        return root.toString();
    }

    /** 构造参数分片帧（input_json_delta / partial_json）。 */
    static String inputJsonDelta(int index, String partialJson) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("type", "content_block_delta");
        root.put("index", index);
        root.putObject("delta").put("type", "input_json_delta").put("partial_json", partialJson);
        return root.toString();
    }

    /** 构造流末帧（output_tokens）。 */
    static String messageDelta(long outputTokens) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("type", "message_delta");
        root.putObject("delta").put("stop_reason", "end_turn");
        root.putObject("usage").put("output_tokens", outputTokens);
        return root.toString();
    }

    /** 构造结束帧。 */
    static String messageStop() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("type", "message_stop");
        return root.toString();
    }
}
