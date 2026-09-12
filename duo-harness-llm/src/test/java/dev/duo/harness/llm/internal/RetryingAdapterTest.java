package dev.duo.harness.llm.internal;

import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.RetryingAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试装饰器用例：可重试异常（5xx/429）按退避重试直至成功或耗尽、协议与凭证
 * 错误（400/401）直通不重试、耗尽后错误原样呈现、流式安全（已交付增量不重试语义
 * 由计数 sink 保证退避路径只在零交付时发生）。
 */
class RetryingAdapterTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：RetryingAdapterTest —— LLM 重试：5xx 退避重试直至成功、"
                + "429 同路、400/401 直通不重试、耗尽原样呈现（5 用例） ===");
    }

    private final MockOpenAiServer server = new MockOpenAiServer();

    @AfterEach
    void tearDown() {
        server.stop();
    }

    /** 被测装饰器（测试用 1ms 退避，不拖慢套件）。 */
    private RetryingAdapter adapter() {
        return new RetryingAdapter(
                new OpenAiCompatAdapter(new LlmConfig(server.baseUrl(), "sk-test", "test-model",
                        LlmConfig.DEFAULT_SYSTEM_PROMPT)), 3, 1);
    }

    @Test
    void retryableErrorThenSuccessDeliversTurn() {
        server.respondSequentially(List.of(
                MockOpenAiServer.errorScript(502, "{\"error\":{\"message\":\"bad gateway\"}}"),
                MockOpenAiServer.sseScript(List.of(MockOpenAiServer.deltaChunk("恢复")))));

        LlmTurn turn = adapter().streamTurn(new ChatRequest("s", List.of(ChatMessage.user("q"))),
                s -> { });

        assertEquals("恢复", turn.text(), "首次 502 重试后成功");
        assertEquals(2, server.requestCount(), "共两次请求（1 失败 + 1 成功）");
    }

    @Test
    void tooManyRequests429IsRetried() {
        server.respondSequentially(List.of(
                MockOpenAiServer.errorScript(429, "{\"error\":{\"message\":\"rate limited\"}}"),
                MockOpenAiServer.sseScript(List.of(MockOpenAiServer.deltaChunk("ok")))));

        LlmTurn turn = adapter().streamTurn(new ChatRequest("s", List.of(ChatMessage.user("q"))),
                s -> { });

        assertEquals("ok", turn.text(), "429 与 5xx 同走可重试路径");
        assertEquals(2, server.requestCount());
    }

    @Test
    void protocolErrorsAreNotRetried() {
        server.respondError(400, "{\"error\":{\"message\":\"missing field\"}}");

        PluginException e = assertThrows(PluginException.class,
                () -> adapter().streamTurn(new ChatRequest("s", List.of(ChatMessage.user("q"))), s -> { }));

        assertTrue(e.getMessage().contains("400"), e.getMessage());
        assertEquals(1, server.requestCount(), "协议错误绝不重试——立即暴露");
    }

    @Test
    void unauthorizedIsNotRetried() {
        server.respondError(401, "{\"error\":{\"message\":\"bad api key\"}}");

        assertThrows(PluginException.class,
                () -> adapter().stream(new ChatRequest("s", List.of(ChatMessage.user("q"))), c -> { }));

        assertEquals(1, server.requestCount());
    }

    @Test
    void exhaustedRetriesSurfaceLastError() {
        server.respondError(503, "{\"error\":{\"message\":\"unavailable\"}}");

        PluginException e = assertThrows(PluginException.class,
                () -> adapter().streamTurn(new ChatRequest("s", List.of(ChatMessage.user("q"))), s -> { }));

        assertTrue(e.getMessage().contains("503"), e.getMessage());
        assertEquals(3, server.requestCount(), "默认 3 次尝试（1 + 2 重试）后耗尽");
    }

    @Test
    void streamPathRetriesToo() throws IOException {
        server.respondSequentially(List.of(
                MockOpenAiServer.errorScript(503, "{\"error\":{\"message\":\"unavailable\"}}"),
                MockOpenAiServer.sseScript(List.of(MockOpenAiServer.deltaChunk("直答")))));

        List<String> chunks = new ArrayList<>();
        adapter().stream(new ChatRequest("s", List.of(ChatMessage.user("q"))),
                chunk -> chunks.add(chunk.text()));

        assertEquals(List.of("直答"), chunks, "stream 形态同样受益于重试");
        assertEquals(2, server.requestCount());
    }
}
