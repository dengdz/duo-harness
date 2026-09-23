package dev.duo.harness.llm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 可换执行链装饰器用例（M24 工单 09，ADR-0026 决策六）：初始委托生效、swap 原子
 * 替换（新请求走新链）、null 拒绝。
 */
class SwappableLlmAdapterTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SwappableLlmAdapterTest —— 可换执行链：委托路由与原子替换（3 用例） ===");
    }

    /** 记录收到的请求并返回固定回复的桩适配器。 */
    private static LlmAdapter stub(String tag, AtomicReference<String> lastTag) {
        return new LlmAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                if (lastTag != null) {
                    lastTag.set(tag);
                }
                onChunk.accept(new ChatChunk(tag));
            }

            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                if (lastTag != null) {
                    lastTag.set(tag);
                }
                textSink.accept(tag);
                return new LlmTurn(tag, List.of(), null, null);
            }
        };
    }

    @Test
    void initialDelegateServesUntilSwap() {
        SwappableLlmAdapter adapter = new SwappableLlmAdapter(stub("旧链", null));
        StringBuilder text = new StringBuilder();
        LlmTurn turn = adapter.streamTurn(new ChatRequest("s", List.of(ChatMessage.user("hi"))), text::append);
        assertEquals("旧链", turn.text());

        adapter.swap(stub("新链", null));
        LlmTurn swapped = adapter.streamTurn(new ChatRequest("s", List.of(ChatMessage.user("hi"))), text::append);
        assertEquals("新链", swapped.text(), "swap 后新请求走新链");
    }

    @Test
    void nullSwapRejected() {
        SwappableLlmAdapter adapter = new SwappableLlmAdapter(stub("旧链", null));
        assertThrows(NullPointerException.class, () -> adapter.swap(null));
    }

    @Test
    void streamDelegatesThroughSwap() {
        AtomicReference<String> last = new AtomicReference<>();
        SwappableLlmAdapter adapter = new SwappableLlmAdapter(stub("A", last));
        List<String> chunks = new java.util.ArrayList<>();
        adapter.stream(new ChatRequest("s", List.of(ChatMessage.user("hi"))), c -> chunks.add(c.text()));
        assertEquals(List.of("A"), chunks);

        adapter.swap(stub("B", last));
        chunks.clear();
        adapter.stream(new ChatRequest("s", List.of(ChatMessage.user("hi"))), c -> chunks.add(c.text()));
        assertEquals(List.of("B"), chunks);
        assertSame("B", last.get());
    }
}
