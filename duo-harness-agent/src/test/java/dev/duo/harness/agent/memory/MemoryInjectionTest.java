package dev.duo.harness.agent.memory;

import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.internal.ToolCallingAgent;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆注入用例（M25 工单 02）：meta_user 记忆段置于消息序列最前（先于对话历史，
 * 后于治理投影）、请求视图专用不落会话日志、缺席/未装配零注入。
 */
class MemoryInjectionTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：MemoryInjectionTest —— 记忆注入：消息序列最前、不落会话、降级零注入（3 用例） ===");
    }

    @TempDir
    Path tempDir;

    private Session newSession() throws IOException {
        return Session.create(tempDir.resolve("sessions"));
    }

    /** 单轮直答 mock：捕获请求后收口。 */
    private LlmAdapter capturingAdapter(List<ChatRequest> captured) {
        return new LlmAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                throw new UnsupportedOperationException("测试主循环走 streamTurn");
            }

            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                captured.add(request);
                textSink.accept("收到");
                return new LlmTurn("收到", List.of());
            }
        };
    }

    /** 空工具域（请求组装不依赖工具在册）。 */
    private static class NoTools implements ToolsService {
        @Override
        public Disposable register(Context registrant, ToolDefinition definition) {
            throw new UnsupportedOperationException("测试不走 register");
        }

        @Override
        public Disposable guard(Context registrant, dev.duo.harness.tools.GuardCheck check) {
            throw new UnsupportedOperationException("测试不走 guard");
        }

        @Override
        public List<ToolDefinition> list() {
            return List.of();
        }

        @Override
        public ToolResult execute(String toolName, com.fasterxml.jackson.databind.JsonNode args) {
            throw new UnsupportedOperationException("测试不走 execute");
        }
    }

    @Test
    void 记忆段置于消息序列最前且不落会话() throws IOException {
        Path file = tempDir.resolve("MEMORY.md");
        Files.writeString(file, "- 用户偏好中文回复\n");
        Session session = newSession();
        List<ChatRequest> captured = new ArrayList<>();
        ToolCallingAgent agent = new ToolCallingAgent(
                capturingAdapter(captured), new NoTools(), session,
                new dev.duo.harness.agent.prompt.PromptRegistry("测试提示"), 10, 1,
                null, null, null, false, null, null,
                new MemoryBook(file, MemoryBook.DEFAULT_BUDGET_CHARS));

        agent.send("这个项目记了什么", AgentListener.NONE);

        assertEquals(1, captured.size(), "单轮直答一次请求");
        List<ChatMessage> messages = captured.get(0).messages();
        assertEquals(2, messages.size(), "记忆段 + 用户消息: " + messages.size());
        ChatMessage head = messages.get(0);
        assertEquals(ChatMessage.Role.USER, head.role(), "meta_user 记忆段为 user 角色");
        assertTrue(head.content().startsWith("<memory>"), "记忆段以 memory 标签包裹: " + head.content());
        assertTrue(head.content().contains("- 用户偏好中文回复"), "记忆内容随段注入");
        assertEquals("这个项目记了什么", messages.get(1).content(), "对话历史紧随其后");
        // 请求视图专用：会话投影不含记忆段文本（注入不落会话日志）
        assertTrue(session.deriveMessages().stream()
                        .noneMatch(m -> String.valueOf(m.content()).contains("用户偏好中文回复")),
                "记忆段不得进会话日志");
        session.close();
    }

    @Test
    void 文件缺席零注入() throws IOException {
        Session session = newSession();
        List<ChatRequest> captured = new ArrayList<>();
        ToolCallingAgent agent = new ToolCallingAgent(
                capturingAdapter(captured), new NoTools(), session,
                new dev.duo.harness.agent.prompt.PromptRegistry("测试提示"), 10, 1,
                null, null, null, false, null, null,
                new MemoryBook(tempDir.resolve("MEMORY.md"), MemoryBook.DEFAULT_BUDGET_CHARS));

        agent.send("你好", AgentListener.NONE);

        assertTrue(captured.get(0).messages().stream()
                        .noneMatch(m -> m.content().contains("<memory>")),
                "记忆本缺席 → 请求零记忆段（未启用用户零感知）");
        session.close();
    }

    @Test
    void 未装配记忆服务零注入() throws IOException {
        Session session = newSession();
        List<ChatRequest> captured = new ArrayList<>();
        ToolCallingAgent agent = new ToolCallingAgent(
                capturingAdapter(captured), new NoTools(), session,
                new dev.duo.harness.agent.prompt.PromptRegistry("测试提示"), 10, 1,
                null, null, null, false, null, null, null);

        agent.send("你好", AgentListener.NONE);

        assertFalse(captured.get(0).systemPrompt().contains("memory"),
                "未装配 → 无记忆相关注入");
        assertTrue(captured.get(0).messages().stream()
                        .noneMatch(m -> m.content().contains("<memory>")),
                "未装配 → 请求零记忆段");
        session.close();
    }
}
