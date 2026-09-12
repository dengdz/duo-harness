package dev.duo.harness.example.chat;

import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天演示冒烟（M4）：会话记忆端到端——第二次运行的请求含第一轮对话内容
 * （多轮记忆）、自动继续最新会话（横幅显示会话 id 与消息数）、/new 清空
 * 上下文、错误呈现后可继续。
 */
class ChatReplMainTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ChatReplMainTest —— 聊天演示冒烟（M4）：多轮记忆端到端 +"
                + " 自动继续 + /new 清空 + 错误呈现（1 用例） ===");
    }

    /** 记录型 mock 适配器：捕获每次请求的消息历史；"出错"轮模拟流中途失败。 */
    private static final class RecordingAdapter implements LlmAdapter {

        final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
            requests.add(request);
            String last = request.messages().get(request.messages().size() - 1).content();
            if (last.startsWith("触发错误")) {
                // 模拟流中途失败：已交付一段增量后抛错
                onChunk.accept(new ChatChunk("部分"));
                throw new PluginException("LLM 调用失败: HTTP 429 - 限流");
            }
            onChunk.accept(new ChatChunk("收到：" + last));
        }

        @Override
        public dev.duo.harness.llm.LlmTurn streamTurn(ChatRequest request,
                                                      java.util.function.Consumer<String> textSink) {
            // 直答委托：与 stream 同路径（M4 冒烟不触工具循环）
            List<ChatChunk> chunks = new ArrayList<>();
            stream(request, onChunk -> {
                chunks.add(onChunk);
                textSink.accept(onChunk.text());
            });
            String text = chunks.stream().map(ChatChunk::text)
                    .collect(java.util.stream.Collectors.joining());
            return new dev.duo.harness.llm.LlmTurn(text, List.of());
        }
    }

    @Test
    void replNarratesMultiTurnMemoryResumeNewAndError(@TempDir Path sessionsDir) throws Exception {
        RecordingAdapter adapter = new RecordingAdapter();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);

        // 第一进程生命周期：两轮对话（建立记忆）→ 一轮流中途失败 → /new 清空 → 一轮验证清空 → /exit
        BufferedReader in = new BufferedReader(new StringReader(
                "我叫小红\n我养了一只猫\n触发错误\n/new\n我的猫叫什么\n/exit\n"));
        ChatReplMain.run(in, out, adapter, sessionsDir, "你是助手");

        // 第二进程生命周期：自动继续最近活动会话（/new 的新会话），追问指代
        BufferedReader secondRun = new BufferedReader(new StringReader(
                "我的猫叫什么名字\n/exit\n"));
        ChatReplMain.run(secondRun, out, adapter, sessionsDir, "你是助手");

        String output = buffer.toString(StandardCharsets.UTF_8);

        // 请求历史：/new 前 4 次（两轮 + 错误轮 + /new 后验证轮），恢复后 1 次
        assertEquals(5, adapter.requests.size(), "五次对话请求");
        ChatRequest first = adapter.requests.get(0);
        assertEquals(1, first.messages().size(), "首轮只有一条用户消息");
        ChatRequest second = adapter.requests.get(1);
        assertEquals(3, second.messages().size(), "第二轮应含第一轮 user+assistant+新消息");
        assertEquals("我叫小红", second.messages().get(0).content());
        // /new 清空：新会话的请求只有当前消息
        ChatRequest afterNew = adapter.requests.get(3);
        assertEquals(1, afterNew.messages().size(), "/new 后上下文应清空");
        assertEquals("我的猫叫什么", afterNew.messages().get(0).content());
        // 自动恢复：第二进程 latest 加载 /new 的新会话，请求投影含其历史 + 新输入
        ChatRequest resumed = adapter.requests.get(4);
        assertEquals(3, resumed.messages().size(), "恢复后请求应含会话历史 + 新输入");
        assertEquals("我的猫叫什么", resumed.messages().get(0).content());
        assertEquals("我的猫叫什么名字", resumed.messages().get(2).content());

        // 横幅与交互叙述
        assertTrue(output.contains("继续会话 "), "第二次启动应显示自动继续横幅: " + output);
        assertTrue(output.contains("已有 2 条消息"), "第二次启动时应显示 /new 会话投影消息数: " + output);
        assertTrue(output.contains("[错误] LLM 调用失败: HTTP 429 - 限流"), output);
        assertTrue(output.contains("已开新会话"), output);
        // 收尾：两次运行各自收尾
        assertTrue(countOf(output, "=== 对话结束 ===") == 2, "两次运行各自收尾: " + output);
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
