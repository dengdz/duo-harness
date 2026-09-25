package dev.duo.harness.session;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * microcompact 投影用例（M25 工单 04）：裁剪点事件使对应 tool/result 投影为占位
 * 标记（JSONL 原文不动——可回放）、多次裁剪名单累积、压缩点重置名单、重放恢复。
 */
class SessionMicrocompactTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SessionMicrocompactTest —— microcompact 投影：占位替换、"
                + "名单累积、压缩点重置、重放恢复（4 用例） ===");
    }

    @TempDir
    Path tempDir;

    private Session newSession() throws IOException {
        return Session.create(tempDir.resolve("sessions"));
    }

    @Test
    void 裁剪点后的对应结果投影为占位() throws IOException {
        Session session = newSession();
        session.append(SessionEvent.userMessage("问题"));
        session.append(SessionEvent.toolCall("c1", "read", "{}"));
        session.append(SessionEvent.toolResult("c1", "read", "原始长结果"));
        session.append(SessionEvent.toolCall("c2", "bash", "{}"));
        session.append(SessionEvent.toolResult("c2", "bash", "保留的结果"));
        session.append(SessionEvent.microcompact(List.of("c1"), 100));

        List<Message> messages = session.deriveMessages();
        Message cleared = messages.stream()
                .filter(m -> "c1".equals(m.toolCallId())).findFirst().orElseThrow();
        assertEquals(SessionEvent.MICROCOMPACT_CLEARED_MARKER, cleared.content(),
                "被裁结果投影为占位标记");
        Message kept = messages.stream()
                .filter(m -> "c2".equals(m.toolCallId())).findFirst().orElseThrow();
        assertEquals("保留的结果", kept.content(), "未入名单的结果原样投影");
        // JSONL 原文不动（可回放可审计）
        assertTrue(session.events().stream()
                        .filter(e -> "c1".equals(e.toolCallId()) && SessionEvent.TOOL_RESULT.equals(e.type()))
                        .findFirst().orElseThrow().text().contains("原始长结果"),
                "日志原文完整保留");
        session.close();
    }

    @Test
    void 多次裁剪名单累积() throws IOException {
        Session session = newSession();
        session.append(SessionEvent.userMessage("问题"));
        session.append(SessionEvent.toolCall("c1", "read", "{}"));
        session.append(SessionEvent.toolResult("c1", "read", "一"));
        session.append(SessionEvent.toolCall("c2", "bash", "{}"));
        session.append(SessionEvent.toolResult("c2", "bash", "二"));
        session.append(SessionEvent.microcompact(List.of("c1"), 10));
        session.append(SessionEvent.microcompact(List.of("c2"), 10));

        List<Message> messages = session.deriveMessages();
        assertTrue(messages.stream()
                        .filter(m -> "c1".equals(m.toolCallId())).findFirst().orElseThrow()
                        .content().contains("已清除"),
                "第一次裁剪生效");
        assertTrue(messages.stream()
                        .filter(m -> "c2".equals(m.toolCallId())).findFirst().orElseThrow()
                        .content().contains("已清除"),
                "第二次裁剪名单与首次累积");
        session.close();
    }

    @Test
    void 压缩点重置裁剪名单() throws IOException {
        Session session = newSession();
        session.append(SessionEvent.userMessage("问题"));
        session.append(SessionEvent.toolCall("c1", "read", "{}"));
        session.append(SessionEvent.toolResult("c1", "read", "原文"));
        session.append(SessionEvent.microcompact(List.of("c1"), 10));
        session.append(SessionEvent.compaction("本会话早前让 AI 记了一条验证内容", "manual"));
        session.append(SessionEvent.userMessage("新问题"));
        session.append(SessionEvent.toolCall("c1", "read", "{}"));
        session.append(SessionEvent.toolResult("c1", "read", "压缩后的同名调用结果"));

        List<Message> messages = session.deriveMessages();
        Message after = messages.stream()
                .filter(m -> "c1".equals(m.toolCallId())
                        && "压缩后的同名调用结果".equals(m.content()))
                .findFirst()
                .orElse(null);
        assertTrue(after != null, "压缩点后同名调用结果原样投影（名单已重置）");
        session.close();
    }

    @Test
    void 重放恢复裁剪态() throws IOException {
        Session first = newSession();
        first.append(SessionEvent.userMessage("问题"));
        first.append(SessionEvent.toolCall("c1", "read", "{}"));
        first.append(SessionEvent.toolResult("c1", "read", "原始长结果"));
        first.append(SessionEvent.microcompact(List.of("c1"), 50));
        String id = first.id();
        first.close();

        Session reopened = Session.load(
                tempDir.resolve("sessions").resolve(id + ".jsonl"));
        List<Message> messages = reopened.deriveMessages();
        assertEquals(SessionEvent.MICROCOMPACT_CLEARED_MARKER, messages.stream()
                        .filter(m -> "c1".equals(m.toolCallId())).findFirst().orElseThrow().content(),
                "重开（resume 同路径）后裁剪态经日志重放恢复");
        reopened.close();
    }
}
