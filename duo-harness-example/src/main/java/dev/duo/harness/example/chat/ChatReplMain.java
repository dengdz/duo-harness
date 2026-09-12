package dev.duo.harness.example.chat;

import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.llm.internal.OpenAiCompatAdapter;
import dev.duo.harness.session.Message;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * M4 聊天演示入口：REPL 循环 + 会话记忆——每轮对话写入会话日志，
 * 请求携带完整投影历史（多轮记忆）；启动自动继续最新会话，`/new` 开新话题。
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
        Path sessionsDir = DuoHome.resolve().resolveDir("sessions");
        LlmAdapter adapter = new OpenAiCompatAdapter(config);
        run(in, out, adapter, sessionsDir, config.systemPrompt());
    }

    /** REPL 循环：会话事件写入 + 投影多轮记忆 + 流式打印 + /new 与 /exit。 */
    public static void run(BufferedReader in, PrintStream out, LlmAdapter adapter,
                           Path sessionsDir, String systemPrompt) throws Exception {
        out.println("=== duo-harness Chat Demo（M4：多轮对话 + 会话记忆）===");

        Session session = Session.latest(sessionsDir);
        boolean resumed = session != null;
        if (session == null) {
            session = Session.create(sessionsDir);
        }
        out.println((resumed
                ? "继续会话 " + session.id() + "（已有 " + session.deriveMessages().size() + " 条消息）"
                : "新会话 " + session.id()) + "。/exit 退出，/new 开新会话。");
        out.flush();

        while (true) {
            out.print("你> ");
            out.flush();
            String line = in.readLine();
            if (line == null || line.strip().equals("/exit")) {
                break;
            }
            if (line.strip().equals("/new")) {
                session = Session.create(sessionsDir);
                out.println("已开新会话 " + session.id());
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            converse(out, adapter, session, systemPrompt, line.strip());
        }
        out.println("=== 对话结束 ===");
        out.flush();
    }

    /**
     * 一轮对话：用户输入入日志 → 投影转换 → 流式调用 → 输出与完整回复入日志。
     *
     * <p>失败行为：调用失败（网络/凭证/限流）时错误原样呈现、不写
     * assistant/message——本轮历史以 user 消息收尾，下轮投影保持该缺口
     * （OpenAI 兼容协议容忍连续同角色消息）。</p>
     */
    private static ChatMessage.Role wireRole(Message.Role role) {
        return switch (role) {
            case USER -> ChatMessage.Role.USER;
            case ASSISTANT -> ChatMessage.Role.ASSISTANT;
            case TOOL -> ChatMessage.Role.TOOL;
        };
    }

    private static void converse(PrintStream out, LlmAdapter adapter, Session session,
                                 String systemPrompt, String userText) {
        session.append(SessionEvent.userMessage(userText));

        List<ChatMessage> history = session.deriveMessages().stream()
                .map(message -> new ChatMessage(wireRole(message.role()), message.content(), null, null))
                .toList();

        out.print("AI> ");
        out.flush();
        StringBuilder reply = new StringBuilder();
        try {
            adapter.stream(new ChatRequest(systemPrompt, history), chunk -> {
                out.print(chunk.text());
                out.flush();
                reply.append(chunk.text());
            });
            session.append(SessionEvent.assistantMessage(reply.toString()));
        } catch (PluginException e) {
            out.println("  [错误] " + e.getMessage());
        }
        out.println();
        out.flush();
    }
}
