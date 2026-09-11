package dev.duo.harness.example.chat;

import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.internal.LlmConfig;
import dev.duo.harness.llm.internal.OpenAiCompatAdapter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;

/**
 * M3 聊天演示入口：REPL 循环——每轮独立调用 LLM（无上下文记忆，记忆属 M4）。
 *
 * <p>前置：{@code ~/.duo/config.yml} 配置 llm 段（baseUrl/apiKey/model，
 * systemPrompt 可选）。未配置时启动即给出重配指引。</p>
 *
 * <p>运行：{@code mvn -pl duo-harness-example -am package exec:java
 * -Dexec.mainClass=dev.duo.harness.example.chat.ChatReplMain}</p>
 */
public final class ChatReplMain {

    private ChatReplMain() {
    }

    public static void main(String[] args) throws Exception {
        run(new BufferedReader(new InputStreamReader(System.in)), System.out);
    }

    /** 可测入口（冒烟测试经它注入脚本输入与 mock 适配器）。 */
    public static void run(BufferedReader in, PrintStream out) throws Exception {
        LlmConfig config;
        try {
            config = LlmConfig.load();
        } catch (PluginException e) {
            out.println("LLM 未配置：");
            out.println("  " + e.getMessage());
            out.println("示例（~/.duo/config.yml）：");
            out.println("  llm:");
            out.println("    baseUrl: https://api.deepseek.com");
            out.println("    apiKey: <你的 key>");
            out.println("    model: deepseek-chat");
            out.flush();
            return;
        }
        out.println("=== duo-harness Chat Demo（M3：模型单次对话，每轮独立无记忆）===");
        out.println("模型: " + config.model() + " @ " + config.baseUrl());
        out.println("/exit 退出。");
        run(in, out, new OpenAiCompatAdapter(config), config.systemPrompt());
    }

    /** REPL 循环：读输入 → 流式打印回答 → 下一轮；/exit 或 EOF 退出；错误呈现后继续。 */
    public static void run(BufferedReader in, PrintStream out, LlmAdapter adapter,
                           String systemPrompt) throws Exception {
        while (true) {
            out.print("你> ");
            out.flush();
            String line = in.readLine();
            if (line == null || line.strip().equals("/exit")) {
                break;
            }
            if (line.isBlank()) {
                continue;
            }
            out.print("AI> ");
            out.flush();
            try {
                adapter.stream(new ChatRequest(systemPrompt, line.strip()),
                        chunk -> {
                            out.print(chunk.text());
                            out.flush();
                        });
            } catch (PluginException e) {
                out.println("  [错误] " + e.getMessage());
            }
            out.println();
            out.flush();
        }
        out.println("=== 对话结束 ===");
        out.flush();
    }
}
