package dev.duo.harness.example.chat;

import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天演示冒烟：mock 适配器（固定 chunk 流）+ 脚本输入，断言 REPL 叙述——
 * 提示符、流式拼接、空行跳过、错误呈现后可继续、/exit 干净退出。
 */
class ChatReplMainTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ChatReplMainTest —— 聊天演示冒烟：mock 适配器 + 脚本输入，"
                + "覆盖提示符/流式拼接/空行跳过/错误呈现/干净退出（1 用例） ===");
    }

    @Test
    void replNarratesConversation() throws Exception {
        BufferedReader in = new BufferedReader(new StringReader(
                "你好\n\n会出错的\n/exit\n"));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        LlmAdapter mock = new LlmAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                if (request.userMessage().equals("你好")) {
                    // 两段增量，验证 REPL 按序拼接
                    onChunk.accept(new ChatChunk("你好"));
                    onChunk.accept(new ChatChunk("！"));
                } else {
                    throw new PluginException("LLM 调用失败: HTTP 429 - 限流");
                }
            }
        };

        ChatReplMain.run(in, new PrintStream(buffer, true, StandardCharsets.UTF_8), mock,
                "你是一个演示助手");

        String output = buffer.toString(StandardCharsets.UTF_8);
        // 正常轮：流式两段按序拼接
        assertTrue(output.contains("你> AI> 你好！"), "A0 首轮叙述: " + output);
        assertTrue(output.contains("AI> 你好！"), output);
        // 提示符 4 次：你好轮、空行跳过后、"会出错的"轮、/exit 前的最后一问
        assertTrue(countOf(output, "你> ") == 4, "A1 提示符计数=" + countOf(output, "你> ") + ": " + output);
        // 错误轮：错误呈现且不影响后续
        assertTrue(output.contains("[错误] LLM 调用失败: HTTP 429 - 限流"), "A2 错误呈现: " + output);
        // 干净退出
        assertTrue(output.contains("=== 对话结束 ==="), "A3 结束: " + output);
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
