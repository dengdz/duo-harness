package dev.duo.harness.agent.governance;

import dev.duo.harness.session.AttachmentRef;
import dev.duo.harness.session.Message;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * microcompact 选择器矩阵（M25 工单 04）：白名单内外、失败豁免、媒体豁免、
 * 最近 N 组保留、最短候选、最小节省放弃、顺序保持。
 */
class MicrocompactTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：MicrocompactTest —— 裁剪选择器：白名单、豁免、"
                + "分组保留、最小节省（8 用例） ===");
    }

    /** 候选结果：2100 字符 ≈ 525 tokens，过最短候选线与最小节省线。 */
    private static String bulk() {
        return "r".repeat(2100);
    }

    private static Message tool(String callId, String content) {
        return Message.tool(callId, content);
    }

    /** 单条工具元数据（id → 名 + 失败标志）。 */
    private static Microcompact.ToolMeta meta(String toolName, boolean error) {
        return new Microcompact.ToolMeta(toolName, error);
    }

    @Test
    void 白名单外的工具结果不清() {
        List<Message> messages = new ArrayList<>(List.of(
                new Message(Message.Role.USER, "q1"),
                Message.assistantWithToolCalls(List.of(new dev.duo.harness.session.ToolCall(
                        "c1", "memory_write", "{}"))),
                tool("c1", bulk()),
                new Message(Message.Role.USER, "q2"),
                new Message(Message.Role.USER, "q3"),
                new Message(Message.Role.USER, "q4"),
                new Message(Message.Role.USER, "q5"),
                new Message(Message.Role.USER, "q6"),
                new Message(Message.Role.USER, "q7")));
        List<String> cleared = Microcompact.select(messages,
                Map.of("c1", meta("memory_write", false)), 5);
        assertTrue(cleared.isEmpty(), "memory_write 不在白名单: " + cleared);
    }

    @Test
    void 失败结果豁免保留() {
        List<Message> messages = new ArrayList<>(List.of(
                new Message(Message.Role.USER, "q1"),
                Message.assistantWithToolCalls(List.of(new dev.duo.harness.session.ToolCall(
                        "c1", "bash", "{}"))),
                tool("c1", bulk()),
                new Message(Message.Role.USER, "q2"),
                new Message(Message.Role.USER, "q3"),
                new Message(Message.Role.USER, "q4"),
                new Message(Message.Role.USER, "q5"),
                new Message(Message.Role.USER, "q6"),
                new Message(Message.Role.USER, "q7")));
        List<String> cleared = Microcompact.select(messages,
                Map.of("c1", meta("bash", true)), 5);
        assertTrue(cleared.isEmpty(), "报告失败的结果是排障依据——豁免: " + cleared);
    }

    @Test
    void 携带附件引用的媒体结果豁免() {
        List<Message> messages = new ArrayList<>(List.of(
                new Message(Message.Role.USER, "q1"),
                Message.assistantWithToolCalls(List.of(new dev.duo.harness.session.ToolCall(
                        "c1", "read", "{}"))),
                Message.toolWithAttachments("c1", bulk(),
                        List.of(new AttachmentRef("a1", "image/png", 100, "x.png"))),
                new Message(Message.Role.USER, "q2"),
                new Message(Message.Role.USER, "q3"),
                new Message(Message.Role.USER, "q4"),
                new Message(Message.Role.USER, "q5"),
                new Message(Message.Role.USER, "q6"),
                new Message(Message.Role.USER, "q7")));
        List<String> cleared = Microcompact.select(messages,
                Map.of("c1", meta("read", false)), 5);
        assertTrue(cleared.isEmpty(), "媒体上下文不清: " + cleared);
    }

    @Test
    void 最近五组完整保留之外的旧结果入选() {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(Message.Role.USER, "最早一轮"));
        messages.add(Message.assistantWithToolCalls(List.of(new dev.duo.harness.session.ToolCall(
                "old", "bash", "{}"))));
        messages.add(tool("old", bulk()));
        for (int i = 2; i <= 6; i++) {
            messages.add(new Message(Message.Role.USER, "q" + i));
            messages.add(Message.assistantWithToolCalls(List.of(new dev.duo.harness.session.ToolCall(
                    "c" + i, "bash", "{}"))));
            messages.add(tool("c" + i, bulk()));
        }
        List<String> cleared = Microcompact.select(messages,
                Map.of("old", meta("bash", false), "c2", meta("bash", false),
                        "c3", meta("bash", false), "c4", meta("bash", false),
                        "c5", meta("bash", false), "c6", meta("bash", false)), 5);
        assertEquals(List.of("old"), cleared,
                "6 组中最早一组（含 5 组保留窗口外）入选，最近 5 组不动");
    }

    @Test
    void 组数不足时全保留() {
        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            messages.add(new Message(Message.Role.USER, "q" + i));
            messages.add(Message.assistantWithToolCalls(List.of(new dev.duo.harness.session.ToolCall(
                    "c" + i, "read", "{}"))));
            messages.add(tool("c" + i, bulk()));
        }
        List<String> cleared = Microcompact.select(messages,
                Map.of("c1", meta("read", false), "c2", meta("read", false),
                        "c3", meta("read", false)), 5);
        assertTrue(cleared.isEmpty(), "不足 keepRecent 组——无候选: " + cleared);
    }

    @Test
    void 过短结果不入选() {
        List<Message> messages = new ArrayList<>(List.of(
                new Message(Message.Role.USER, "q1"),
                Message.assistantWithToolCalls(List.of(new dev.duo.harness.session.ToolCall(
                        "c1", "bash", "{}"))),
                tool("c1", "short"),
                new Message(Message.Role.USER, "q2"),
                new Message(Message.Role.USER, "q3"),
                new Message(Message.Role.USER, "q4"),
                new Message(Message.Role.USER, "q5"),
                new Message(Message.Role.USER, "q6"),
                new Message(Message.Role.USER, "q7")));
        List<String> cleared = Microcompact.select(messages,
                Map.of("c1", meta("bash", false)), 5);
        assertTrue(cleared.isEmpty(), "短结果清除无节省价值: " + cleared);
    }

    @Test
    void 总节省低于阈值整轮放弃() {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(Message.Role.USER, "最早"));
        messages.add(Message.assistantWithToolCalls(List.of(new dev.duo.harness.session.ToolCall(
                "old", "bash", "{}"))));
        messages.add(tool("old", "x".repeat(600)));  // 600/4 = 150 tokens < 256
        for (int i = 2; i <= 6; i++) {
            messages.add(new Message(Message.Role.USER, "q" + i));
        }
        List<String> cleared = Microcompact.select(messages,
                Map.of("old", meta("bash", false)), 5);
        assertTrue(cleared.isEmpty(), "节省 150 tokens < 256——不值得落裁剪痕: " + cleared);
    }

    @Test
    void 名单保持投影顺序且同调用不重复() {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(Message.Role.USER, "q1"));
        messages.add(Message.assistantWithToolCalls(List.of(new dev.duo.harness.session.ToolCall(
                "a", "bash", "{}"))));
        messages.add(tool("a", bulk()));
        messages.add(Message.assistantWithToolCalls(List.of(new dev.duo.harness.session.ToolCall(
                "b", "read", "{}"))));
        messages.add(tool("b", bulk()));
        messages.add(Message.assistantWithToolCalls(List.of(new dev.duo.harness.session.ToolCall(
                "a", "bash", "{}"))));
        messages.add(tool("a", bulk()));
        for (int i = 2; i <= 7; i++) {
            messages.add(new Message(Message.Role.USER, "q" + i));
        }
        List<String> cleared = Microcompact.select(messages,
                Map.of("a", meta("bash", false), "b", meta("read", false)), 5);
        assertEquals(List.of("a", "b"), cleared, "顺序保持且同 id 只入选一次");
    }
}
