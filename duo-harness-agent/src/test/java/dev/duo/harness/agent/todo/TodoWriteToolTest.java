package dev.duo.harness.agent.todo;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.internal.ToolCallingAgent;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * todo_write 用例（ADR-0018，工单 M17-03）：整表替换写入与计数回显、校验拒绝面、
 * 投影生命周期（latest-wins / 新 user 消息清空 / 终版回复后保留）、重开会话恢复、
 * agent 循环集成（工具经三段管线执行、事件落日志、投影可读）。
 */
class TodoWriteToolTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：TodoWriteToolTest —— todo 分解抓手：整表替换、投影生命周期、重放恢复（5 用例） ===");
    }

    /** tools 服务视图（与 ToolCallingAgentTest 同包共享形态）。 */
    interface ToolsView {

        ToolsService tools();
    }

    @TempDir
    Path tempDir;

    private Session newSession() throws IOException {
        return Session.create(tempDir.resolve("sessions"));
    }

    private ToolsService toolsWithTodo(Session session) {
        dev.duo.harness.core.api.Context toolsRoot = dev.duo.harness.core.api.Context.root();
        toolsRoot.plugin(new dev.duo.harness.tools.ToolsPlugin(), null).awaitStartup();
        ToolsService impl = toolsRoot.as(ToolsView.class).tools();
        impl.register(toolsRoot, new TodoWriteTool(() -> session));
        return impl;
    }

    private static JsonNode args(String todosJson) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(todosJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void writePersistsEventAndReturnsCounts() throws IOException {
        Session session = newSession();
        ToolsService tools = toolsWithTodo(session);

        String reply = String.valueOf(tools.execute(TodoWriteTool.NAME, args("""
                {"todos":[
                  {"content":"调研现有实现","status":"completed"},
                  {"content":"写方案","status":"in_progress"},
                  {"content":"补测试","status":"pending"}]}""")).value());

        assertEquals("清单已更新：1 待办 / 1 进行中 / 1 已完成。", reply, "计数回显（模型只见这一句）");
        SessionEvent last = session.events().get(session.events().size() - 1);
        assertEquals(SessionEvent.TODO_WRITE, last.type(), "todo/write 事件落日志");
        assertEquals("""
                [{"content":"调研现有实现","status":"completed"},{"content":"写方案","status":"in_progress"},{"content":"补测试","status":"pending"}]""",
                last.text(), "规范化形态（trim 后的内容）整表落盘");
        assertEquals(last.text(), session.todoProjection(), "投影 latest-wins 读到同一份清单");
    }

    @Test
    void validationRejectsBadPayloads() throws IOException {
        Session session = newSession();
        ToolsService tools = toolsWithTodo(session);

        String noSession = String.valueOf(tools.execute(TodoWriteTool.NAME, args("{\"todos\":[]}")).value());
        assertTrue(noSession.startsWith("[todo_write 错误]"), "空数组拒绝: " + noSession);

        String blank = String.valueOf(tools.execute(TodoWriteTool.NAME, args(
                "{\"todos\":[{\"content\":\"  \",\"status\":\"pending\"}]}")).value());
        assertTrue(blank.startsWith("[todo_write 错误]"), "空白 content 拒绝: " + blank);

        String duplicated = String.valueOf(tools.execute(TodoWriteTool.NAME, args("""
                {"todos":[
                  {"content":"同一件事","status":"pending"},
                  {"content":"同一件事","status":"completed"}]}""")).value());
        assertTrue(duplicated.startsWith("[todo_write 错误]"), "重复 content 拒绝: " + duplicated);

        String badStatus = String.valueOf(tools.execute(TodoWriteTool.NAME, args(
                "{\"todos\":[{\"content\":\"任务\",\"status\":\"doing\"}]}")).value());
        assertTrue(badStatus.startsWith("[todo_write 错误]"), "非法 status 拒绝: " + badStatus);

        assertNull(session.todoProjection(), "拒绝路径不落任何事件");
        assertEquals(0, session.events().size(), "会话零写入");
    }

    @Test
    void projectionClearsOnNewUserMessageAndSurvivesFinalReply() throws IOException {
        Session session = newSession();
        ToolsService tools = toolsWithTodo(session);

        tools.execute(TodoWriteTool.NAME, args(
                "{\"todos\":[{\"content\":\"任务\",\"status\":\"in_progress\"}]}"));
        assertNotNull(session.todoProjection(), "写入后投影在场");

        session.append(SessionEvent.assistantMessage("干完了"));
        assertNotNull(session.todoProjection(), "终版回复后保留（用户读完答案还能看到清单）");

        session.append(SessionEvent.userMessage("下一个问题"));
        assertNull(session.todoProjection(), "新 user 消息清空（上一轮清单使命结束）");
    }

    @Test
    void replayRestoresProjectionOnReopen() throws IOException {
        Session session = newSession();
        ToolsService tools = toolsWithTodo(session);
        tools.execute(TodoWriteTool.NAME, args(
                "{\"todos\":[{\"content\":\"重开会话也不丢\",\"status\":\"completed\"}]}"));
        session.append(SessionEvent.assistantMessage("done"));
        Path jsonl = sessionJsonl(session);
        session.close();

        Session reopened = Session.load(jsonl);

        assertNotNull(reopened.todoProjection(), "重开会话经日志重放恢复清单");
        assertTrue(reopened.todoProjection().contains("重开会话也不丢"));
    }

    @Test
    void agentLoopExecutesTodoWriteAndContinues() throws IOException {
        Session session = newSession();
        ToolsService tools = toolsWithTodo(session);
        AtomicInteger round = new AtomicInteger();
        LlmAdapter adapter = new LlmAdapter() {
            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                if (round.incrementAndGet() == 1) {
                    return new LlmTurn("", List.of(new ToolCallRequest("c1", TodoWriteTool.NAME, """
                            {"todos":[{"content":"拆解任务","status":"in_progress"}]}""")), null);
                }
                textSink.accept("按清单推进");
                return new LlmTurn("按清单推进", List.of());
            }

            @Override
            public void stream(ChatRequest request,
                               java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                throw new UnsupportedOperationException("循环路径走 streamTurn");
            }
        };
        ToolCallingAgent agent = new ToolCallingAgent(adapter, tools, session, "你是助手", 10);

        var reply = agent.send("做个多步任务", AgentListener.NONE);

        assertEquals("按清单推进", reply.finalText());
        assertEquals(1, reply.toolInvocations().size(), "todo_write 计一次调用");
        assertEquals(false, reply.toolInvocations().get(0).isError(), "写入成功非错误");
        assertTrue(reply.toolInvocations().get(0).result().startsWith("清单已更新"),
                "回显为计数文本: " + reply.toolInvocations().get(0).result());
        assertNotNull(session.todoProjection(), "agent 循环路径的投影同样可读");
    }

    private Path sessionJsonl(Session session) throws IOException {
        Path dir = tempDir.resolve("sessions");
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().equals(session.id() + ".jsonl"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("会话文件未找到"));
        }
    }
}
