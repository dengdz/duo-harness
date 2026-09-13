package dev.duo.harness.agent;

import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划模式状态推导用例：事件流中最后一次 plan/mode 事件决定形态
 * （entered 激活 / exited 退出 / 无事件不激活）。
 */
class PlanModeTest {

    @TempDir
    Path tempDir;

    @Test
    void stateDerivationFromEventStream() throws IOException {
        Session session = Session.create(tempDir.resolve("sessions"));
        assertFalse(PlanMode.isActive(session), "无 plan/mode 事件 → 不激活");

        session.append(PlanMode.enteredEvent());
        assertTrue(PlanMode.isActive(session), "entered → 激活");

        session.append(SessionEvent.userMessage("期间的用户消息不影响状态"));
        assertTrue(PlanMode.isActive(session), "其他事件不改变状态");

        session.append(PlanMode.exitedEvent());
        assertFalse(PlanMode.isActive(session), "exited → 退出");

        session.append(PlanMode.enteredEvent());
        assertTrue(PlanMode.isActive(session), "再次 entered → 重新激活（最后一次事件决定）");
    }
}
