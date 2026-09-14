package dev.duo.harness.agent;

import dev.duo.harness.session.Message;
import dev.duo.harness.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** spill 卸载用例：超大工具结果落盘 + 预览定位符替换 + 失败保留原结果（治理永不丢数据）。 */
class ContextGovernanceSpillTest {

    @TempDir
    Path tempDir;

    private static final String FALLBACK = "占位";

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ContextGovernanceSpillTest —— spill 卸载：超大结果落盘与预览替换（3 用例） ===");
    }

    private ContextGovernance governance() {
        // spill 阶段不触 LLM（摘要属 compaction）——适配器占位
        return new ContextGovernance(new FailingAdapter());
    }

    /** 占位适配器：spill 阶段任何 LLM 调用都视为缺陷。 */
    private static final class FailingAdapter implements dev.duo.harness.llm.LlmAdapter {
        @Override
        public void stream(dev.duo.harness.llm.ChatRequest request,
                           java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
            throw new UnsupportedOperationException("spill 阶段不应调用 LLM");
        }

        @Override
        public dev.duo.harness.llm.LlmTurn streamTurn(dev.duo.harness.llm.ChatRequest request,
                                                      java.util.function.Consumer<String> textSink) {
            throw new UnsupportedOperationException("spill 阶段不应调用 LLM");
        }
    }

    private Session newSession() throws IOException {
        return Session.create(tempDir.resolve("sessions"));
    }

    private static Message toolMessage(String content) {
        return Message.tool("call_1", content);
    }

    @Test
    void oversizedToolResultSpilledWithPreviewAndLocator() throws IOException {
        Session session = newSession();
        String big = "A".repeat(SPILL_HEAD()) + "B".repeat(SPILL_TAIL());
        List<Message> governed = governance().govern(
                List.of(Message.tool("call_1", big)), session);

        // 原文已落盘
        Path spillDir = session.jsonl().getParent().resolve(session.id()).resolve("spill");
        try (var list = Files.list(spillDir)) {
            assertEquals(1, list.count(), "spill 目录一个文件");
        }
        Path spilled = spillDir.resolve("1-call_1.txt");
        assertEquals(big, Files.readString(spilled), "卸载文件内容为完整原文");

        // 替换消息：预览（头尾都在）+ 定位符；体量大幅下降
        assertEquals(1, governed.size());
        String replacement = governed.getFirst().content();
        assertTrue(replacement.contains(spilled.toString()), "替换文本含定位符路径");
        assertTrue(replacement.startsWith("A".repeat(10)), "预览保留头部");
        assertTrue(replacement.contains("B".repeat(10)), "预览保留尾部");
        assertTrue(replacement.length() < SPILL_THRESHOLD() / 2, "替换后体量大幅低于阈值");
        // 会话目录零写入（治理不动日志）
        assertEquals(0, Files.size(session.jsonl()), "治理不写会话日志");
    }

    @Test
    void smallResultsPassThroughUntouched() throws IOException {
        Session session = newSession();
        List<Message> governed = governance().govern(
                List.of(toolMessage("短结果")), session);
        assertEquals("短结果", governed.getFirst().content(), "阈值内结果原样透传");
    }

    @Test
    void spillFailureKeepsOriginalResult() throws IOException {
        Session session = newSession();
        // 会话 JSONL 父目录下制造同名"文件"阻断 spill 目录创建 → 卸载失败
        Path blocker = session.jsonl().getParent().resolve(session.id());
        Files.createDirectories(blocker.getParent());
        Files.writeString(blocker, "not a directory");

        String big = "X".repeat(SPILL_THRESHOLD() + 1);
        List<Message> governed = governance().govern(List.of(toolMessage(big)), session);
        assertEquals(big, governed.getFirst().content(), "卸载失败保留原结果（治理永不丢数据）");
    }

    private static int SPILL_THRESHOLD() {
        return ContextGovernance.SPILL_THRESHOLD_CHARS;
    }

    private static int SPILL_HEAD() {
        return SPILL_THRESHOLD();  // 头部 = 阈值长度，总长超阈值触发卸载
    }

    private static int SPILL_TAIL() {
        return 500;
    }
}
