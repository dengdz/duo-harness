package dev.duo.harness.llm;

import dev.duo.harness.core.api.PluginException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 思考增量穿透装饰层（M29 工单 06）：Retrying/Swappable 包装器三参透传、缺省丢弃语义。 */
class ReasoningPassthroughTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ReasoningPassthroughTest —— 思考增量穿透装饰层（2 用例） ===");
    }

    /** 三参桩：思考增量与正文增量各自交付。 */
    private static LlmAdapter reasoningStub(List<String> gotReasoning) {
        return new LlmAdapter() {
            @Override
            public void stream(ChatRequest request, Consumer<ChatChunk> onChunk) {
                onChunk.accept(new ChatChunk("答"));
            }

            @Override
            public LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink) {
                throw new AssertionError("三参缺省不应回落到两参（包装器必须透传思考通道）");
            }

            @Override
            public LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink,
                                      Consumer<String> reasoningSink) {
                reasoningSink.accept("思考A");
                reasoningSink.accept("思考B");
                textSink.accept("答");
                return new LlmTurn("答", List.of(), "思考A思考B", null);
            }
        };
    }

    @Test
    void 重试与可换装饰器透传思考增量() {
        List<String> viaRetrying = new ArrayList<>();
        new RetryingAdapter(reasoningStub(viaRetrying)).streamTurn(
                new ChatRequest("s", List.of()), t -> { }, viaRetrying::add);
        assertEquals(List.of("思考A", "思考B"), viaRetrying, "RetryingAdapter 透传思考增量");

        List<String> viaSwappable = new ArrayList<>();
        new SwappableLlmAdapter(reasoningStub(viaSwappable)).streamTurn(
                new ChatRequest("s", List.of()), t -> { }, viaSwappable::add);
        assertEquals(List.of("思考A", "思考B"), viaSwappable, "SwappableLlmAdapter 透传思考增量");
    }

    @Test
    void 未升级适配器走缺省丢弃() {
        // 只实现两参的适配器：三参调用走接口缺省（增量丢弃、聚合不变）——旧实现零破坏
        LlmAdapter legacy = new LlmAdapter() {
            @Override
            public void stream(ChatRequest request, Consumer<ChatChunk> onChunk) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink) {
                textSink.accept("答");
                return new LlmTurn("答", List.of(), null, null);
            }
        };
        List<String> leaked = new ArrayList<>();
        LlmTurn turn = legacy.streamTurn(new ChatRequest("s", List.of()), t -> { }, leaked::add);
        assertTrue(leaked.isEmpty(), "缺省实现丢弃思考增量");
        assertEquals("答", turn.text(), "聚合行为不变");
        assertEquals(null, turn.reasoningContent());
    }
}
