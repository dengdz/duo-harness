package dev.duo.harness.session;

import dev.duo.harness.core.api.PluginException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话事件溯源用例：append 单写（内存 + JSONL 同步落盘）、重放读回一致、
 * 投影规则（message 入列 / chunk 不投影）、空会话、latest 选取。
 */
class SessionTest {

    @TempDir
    Path tempDir;

    private Path sessionsDir() {
        return tempDir.resolve("sessions");
    }

    /** 组装一轮完整对话（用户消息 + 两段 chunk + 完整消息）。 */
    private static void appendRound(Session session, String userText, String assistantText) {
        session.append(SessionEvent.userMessage(userText));
        session.append(SessionEvent.assistantChunk(assistantText + "-1"));
        session.append(SessionEvent.assistantChunk(assistantText + "-2"));
        session.append(SessionEvent.assistantMessage(assistantText));
    }

    @Test
    void appendWritesMemoryAndJsonlTogether() throws IOException {
        Session session = Session.create(sessionsDir());
        session.append(SessionEvent.userMessage("你好"));

        // 内存观测量：事件可见
        assertEquals(1, session.events().size());
        assertEquals(SessionEvent.USER_MESSAGE, session.events().get(0).type());
        // 磁盘观测量：JSONL 逐行落盘
        List<String> lines = Files.readAllLines(session.jsonl());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"type\":\"user/message\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"text\":\"你好\""), lines.get(0));
    }

    @Test
    void loadReplaysIdenticalSequence() {
        Session original = Session.create(sessionsDir());
        appendRound(original, "第一问", "第一答");
        appendRound(original, "第二问", "第二答");

        Session replayed = Session.load(original.jsonl());

        assertEquals(original.id(), replayed.id());
        assertEquals(original.events(), replayed.events(), "读回事件序列应与写入一致");
    }

    @Test
    void deriveMessagesProjectsMessagesOnly() {
        Session session = Session.create(sessionsDir());
        appendRound(session, "第一问", "第一答");
        session.append(SessionEvent.userMessage("第二问"));
        session.append(SessionEvent.assistantChunk("过程细节"));

        List<Message> messages = session.deriveMessages();

        assertEquals(3, messages.size(), "chunk 不投影: " + messages);
        assertEquals(Message.Role.USER, messages.get(0).role());
        assertEquals("第一问", messages.get(0).content());
        assertEquals(Message.Role.ASSISTANT, messages.get(1).role());
        assertEquals("第一答", messages.get(1).content());
        assertEquals(Message.Role.USER, messages.get(2).role());
    }

    @Test
    void emptySessionDerivesEmptyProjection() {
        Session session = Session.create(sessionsDir());

        assertTrue(session.deriveMessages().isEmpty(), "空会话投影应为空列表");
        assertTrue(session.events().isEmpty());
    }

    @Test
    void textPayloadWithQuotesAndNewlineSurvivesRoundTrip() {
        Session original = Session.create(sessionsDir());
        String tricky = "带\"引号\"与\n换行\t的文本";
        original.append(SessionEvent.userMessage(tricky));

        Session replayed = Session.load(original.jsonl());

        assertEquals(tricky, replayed.events().get(0).text(), "特殊字符载荷应原样回放");
    }

    @Test
    void latestPicksNewestById() throws IOException {
        // 手工控制文件名：绕开 id 生成的随机性，确定性验证"字典序最大 = 最新"
        Files.createDirectories(sessionsDir());
        Files.writeString(sessionsDir().resolve("20260101-000000-aaaa.jsonl"), "");
        Files.writeString(sessionsDir().resolve("20260101-000000-bbbb.jsonl"), "");

        Session latest = Session.latest(sessionsDir());

        assertNotNull(latest);
        assertEquals("20260101-000000-bbbb", latest.id(), "应取文件名字典序最大的会话");
    }

    @Test
    void latestReturnsNullWhenDirectoryEmpty() {
        assertNull(Session.latest(sessionsDir()), "空目录应返回 null");
    }

    @Test
    void loadRejectsCorruptLine() throws IOException {
        Path file = sessionsDir().resolve("bad.jsonl");
        Files.createDirectories(sessionsDir());
        Files.writeString(file, "{不是JSON");

        assertThrows(PluginException.class, () -> Session.load(file));
    }
}
