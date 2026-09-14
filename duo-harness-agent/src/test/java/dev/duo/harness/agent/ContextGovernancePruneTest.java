package dev.duo.harness.agent;

import dev.duo.harness.session.Message;
import dev.duo.harness.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工具结果修剪用例：次长结果（spill 阈值内、修剪阈值上）头尾收窄，原文留 JSONL。 */
class ContextGovernancePruneTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ContextGovernancePruneTest —— 工具结果修剪：次长结果收窄（3 用例） ===");
    }

    private ContextGovernance governance() {
        return new ContextGovernance(new NoLlmAdapter());
    }

    /** 占位适配器：修剪阶段不触 LLM。 */
    private static final class NoLlmAdapter implements dev.duo.harness.llm.LlmAdapter {
        @Override
        public void stream(dev.duo.harness.llm.ChatRequest request,
                           java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
            throw new UnsupportedOperationException("修剪阶段不应调用 LLM");
        }

        @Override
        public dev.duo.harness.llm.LlmTurn streamTurn(dev.duo.harness.llm.ChatRequest request,
                                                      java.util.function.Consumer<String> textSink) {
            throw new UnsupportedOperationException("修剪阶段不应调用 LLM");
        }
    }

    @Test
    void overPruneThresholdResultNarrowed() throws IOException {
        Session session = Session.create(tempDir.resolve("sessions"));
        // 5000 字符（>8K 阈值的一半不触发……直接用 10000：> 8K 阈值、< 50K spill 阈值）
        String content = "H".repeat(9_000) + "M".repeat(1_000) + "T".repeat(500);
        List<Message> governed = governance().govern(
                List.of(Message.tool("call_1", content)), session);

        String replacement = governed.getFirst().content();
        assertTrue(replacement.length() < 4_000, "修剪后体量大幅收窄，实际 " + replacement.length());
        assertTrue(replacement.startsWith("H".repeat(100)), "保留头部");
        assertTrue(replacement.contains("T".repeat(100)), "保留尾部");
        assertTrue(replacement.contains("已修剪中段"), "标注修剪字符数");
        assertFalse(replacement.contains("H".repeat(3_000)), "头部深处的中段被移除（仅保留头 2K）");
        // 原文仍在会话日志（治理不写日志，此处仅确认投影外事实由日志承载——日志零写入）
        assertEquals(0, Files_size(session.jsonl()), "治理不写会话日志");
    }

    @Test
    void underPruneThresholdUntouched() throws IOException {
        Session session = Session.create(tempDir.resolve("sessions"));
        List<Message> governed = governance().govern(
                List.of(Message.tool("call_1", "短结果")), session);
        assertEquals("短结果", governed.getFirst().content());
    }

    @Test
    void budgetDropsAfterPrune() throws IOException {
        Session session = Session.create(tempDir.resolve("sessions"));
        List<Message> before = List.of(Message.tool("call_1", "X".repeat(10_000)));
        List<Message> after = governance().govern(before, session);
        assertTrue(ContextBudget.estimateMessageTokens(after)
                        < ContextBudget.estimateMessageTokens(before),
                "修剪后预算下降");
    }

    private static long Files_size(Path path) throws IOException {
        return java.nio.file.Files.size(path);
    }
}
