package dev.duo.harness.llm.internal;

import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.RetryableLlmException;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.llm.LlmTurn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 套件：AdapterParityTest —— 双协议面对拍（C2 工单 04，骨架抽取的行为基线）：
 * 同一行为场景分别驱动 OpenAI 兼容面与 Anthropic 面，断言错误语义、流式增量、
 * 聚合结果同构——骨架抽取（StreamingHttpAdapter）前后本套件保持全绿，
 * 「两协议面行为同构」由本对拍锁死（帧格式不同、行为等价）。
 */
class AdapterParityTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：AdapterParityTest —— 双协议面对拍：错误语义（400 不可重试/429 可重试）、"
                + "流式增量序、聚合结果（文本/工具调用/用量）同构（3 用例） ===");
    }

    private final MockOpenAiServer openai = new MockOpenAiServer();
    private final MockAnthropicServer anthropic = new MockAnthropicServer();

    @AfterEach
    void tearDown() {
        openai.stop();
        anthropic.stop();
    }

    private LlmAdapter openaiFace() {
        return new OpenAiCompatAdapter(new LlmConfig(openai.baseUrl(), "sk-test",
                "test-model", LlmConfig.DEFAULT_SYSTEM_PROMPT));
    }

    private LlmAdapter anthropicFace() {
        return new AnthropicMessagesAdapter(new LlmConfig(anthropic.baseUrl(), "sk-ant-test",
                "claude-test", LlmConfig.DEFAULT_SYSTEM_PROMPT));
    }

    private static ChatRequest request() {
        return new ChatRequest("系统提示", List.of(new ChatMessage(
                ChatMessage.Role.USER, "问", null, null, null)), List.of());
    }

    /** 错误对拍：同一状态码在两面产生同型异常与同构消息（provider 消息保留）。 */
    @Test
    void errorSemanticsIdenticalAcrossFaces() throws IOException {
        for (int statusCode : new int[]{400, 429}) {
            openai.respondError(statusCode, "{\"error\":{\"message\":\"配额不足\"}}");
            anthropic.respondError(statusCode, "{\"error\":{\"message\":\"配额不足\"}}");

            PluginException openaiError = assertThrows(PluginException.class,
                    () -> openaiFace().streamTurn(request(), text -> { }),
                    "openai 面 " + statusCode + " 上抛");
            PluginException anthropicError = assertThrows(PluginException.class,
                    () -> anthropicFace().streamTurn(request(), text -> { }),
                    "anthropic 面 " + statusCode + " 上抛");

            assertEquals("LLM 调用失败: HTTP " + statusCode + " - 配额不足",
                    openaiError.getMessage(), "openai 面消息形态");
            assertEquals(openaiError.getMessage(), anthropicError.getMessage(),
                    "两面消息逐字一致（状态码 " + statusCode + "）");

            boolean retryable = statusCode == 429 || statusCode == 502
                    || statusCode == 503 || statusCode == 504;
            assertEquals(retryable, openaiError instanceof RetryableLlmException,
                    "openai 面可重试分类（" + statusCode + "）");
            assertEquals(retryable, anthropicError instanceof RetryableLlmException,
                    "anthropic 面可重试分类（" + statusCode + "）");
        }
    }

    /** 流式增量对拍：两帧文本在两面产出相同的增量序（chunk 顺序与内容）。 */
    @Test
    void streamChunkSequenceIdenticalAcrossFaces() {
        openai.respondSse(List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}",
                "[DONE]"));
        anthropic.respondSse(List.of(
                MockAnthropicServer.textDelta("你好"),
                MockAnthropicServer.textDelta("，世界"),
                MockAnthropicServer.messageStop()));

        List<String> openaiChunks = new ArrayList<>();
        openaiFace().stream(request(), chunk -> openaiChunks.add(chunk.text()));
        List<String> anthropicChunks = new ArrayList<>();
        anthropicFace().stream(request(), chunk -> anthropicChunks.add(chunk.text()));

        assertEquals(List.of("你好", "，世界"), openaiChunks, "openai 面增量序");
        assertEquals(openaiChunks, anthropicChunks, "两面增量序逐项一致");
    }

    /** 聚合对拍：文本 + 单工具调用 + 用量在两面产出等价的 LlmTurn（帧格式不同、结果等价）。 */
    @Test
    void aggregatedTurnEquivalentAcrossFaces() {
        openai.respondSse(List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"读一下\"}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-7\","
                        + "\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"p\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\"a.txt\\\"}\"}}]}}]}",
                "{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120}}",
                "[DONE]"));
        anthropic.respondSse(List.of(
                MockAnthropicServer.messageStart(100),
                MockAnthropicServer.textDelta("读一下"),
                MockAnthropicServer.toolUseStart(0, "call-7", "read"),
                MockAnthropicServer.inputJsonDelta(0, "{\"p\":"),
                MockAnthropicServer.inputJsonDelta(0, "\"a.txt\"}"),
                MockAnthropicServer.messageDelta(20),
                MockAnthropicServer.messageStop()));

        StringBuilder openaiSink = new StringBuilder();
        LlmTurn openaiTurn = openaiFace().streamTurn(request(), openaiSink::append);
        StringBuilder anthropicSink = new StringBuilder();
        LlmTurn anthropicTurn = anthropicFace().streamTurn(request(), anthropicSink::append);

        assertEquals("读一下", openaiTurn.text());
        assertEquals(openaiTurn.text(), anthropicTurn.text(), "聚合文本一致");
        assertEquals("读一下", openaiSink.toString(), "openai 面增量回调");
        assertEquals(openaiSink.toString(), anthropicSink.toString(), "增量回调一致");

        assertEquals(1, openaiTurn.toolCalls().size(), "openai 面单工具调用");
        assertEquals(1, anthropicTurn.toolCalls().size(), "anthropic 面单工具调用");
        assertEquals(openaiTurn.toolCalls().get(0).id(), anthropicTurn.toolCalls().get(0).id(), "调用 id 一致");
        assertEquals(openaiTurn.toolCalls().get(0).name(), anthropicTurn.toolCalls().get(0).name(), "工具名一致");
        assertEquals(openaiTurn.toolCalls().get(0).argumentsJson(),
                anthropicTurn.toolCalls().get(0).argumentsJson(), "参数分片聚合一致");

        assertEquals(100, openaiTurn.usage().promptTokens(), "openai 面 prompt_tokens");
        assertEquals(openaiTurn.usage().promptTokens(), anthropicTurn.usage().promptTokens(), "prompt 用量一致");
        assertEquals(openaiTurn.usage().completionTokens(), anthropicTurn.usage().completionTokens(), "completion 用量一致");
        assertEquals(openaiTurn.usage().totalTokens(), anthropicTurn.usage().totalTokens(), "total 用量一致");
        assertTrue(openaiTurn.reasoningContent() == null
                        && anthropicTurn.reasoningContent() == null,
                "无思考帧时两面 reasoning 均空");
    }
}
