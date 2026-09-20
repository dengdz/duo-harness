package dev.duo.harness.session;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件引用块（M21 工单 04）：user/attachment 事件的落盘与归属校验数据源
 * （referencedAttachments）；引用块不投影进模型消息——子代理种子（基于投影切片）
 * 天然过滤；发送时序 = 引用事件先于 user/message。
 */
class SessionAttachmentTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SessionAttachmentTest —— 附件引用块：落盘/归属/不投影/时序 ===");
    }

    @Test
    void 引用事件落盘并可回读() {
        Session session = Session.create(tempDir.resolve("s1"));
        AttachmentRef ref = new AttachmentRef("a".repeat(64), "image/png", 1024, "截图.png");
        session.appendUserAttachment(ref);
        session.append(SessionEvent.userMessage("看这张图"));

        assertEquals(1, session.referencedAttachments().size());
        assertEquals(ref, session.referencedAttachments().get(0));
        assertEquals(ref, AttachmentRef.from(session.referencedAttachments().get(0).toJson()).orElseThrow());
    }

    @Test
    void 引用块不投影进模型消息() {
        Session session = Session.create(tempDir.resolve("s2"));
        session.appendUserAttachment(new AttachmentRef("b".repeat(64), "image/png", 2048, "图"));
        session.append(SessionEvent.userMessage("带图消息"));

        assertTrue(session.deriveMessages().stream().noneMatch(m ->
                        m.content().contains("b".repeat(64))),
                "引用块不投影——子代理种子与模型消息天然过滤（工单 04）");
        assertTrue(session.deriveMessages().stream()
                .anyMatch(m -> m.content().equals("带图消息")), "文本消息照常投影");
    }

    @Test
    void 引用事件先于user消息落盘() {
        Session session = Session.create(tempDir.resolve("s3"));
        session.appendUserAttachment(new AttachmentRef("c".repeat(64), "image/png", 1, "先"));
        session.append(SessionEvent.userMessage("后"));

        int refIdx = -1, msgIdx = -1;
        var events = session.events();
        for (int i = 0; i < events.size(); i++) {
            if (SessionEvent.USER_ATTACHMENT.equals(events.get(i).type())) refIdx = i;
            if (SessionEvent.USER_MESSAGE.equals(events.get(i).type())) msgIdx = i;
        }
        assertTrue(refIdx >= 0 && msgIdx > refIdx, "引用事件须先于 user/message（发送时序契约）");
    }

    @Test
    void 坏行跳过不炸扫描() {
        Session session = Session.create(tempDir.resolve("s4"));
        session.append(SessionEvent.userAttachment("{broken"));
        session.appendUserAttachment(new AttachmentRef("d".repeat(64), "image/png", 1, "好的"));
        assertEquals(1, session.referencedAttachments().size(), "坏行跳过不炸扫描");
    }
}
