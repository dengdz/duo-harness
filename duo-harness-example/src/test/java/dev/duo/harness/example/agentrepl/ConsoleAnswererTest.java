package dev.duo.harness.example.agentrepl;

import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 终端回答者用例（ADR-0008 的 M6 呈现位）：审批 y/n 两种作答、EOF/空输入
 * fail-closed、选项序号与自由文本回答、多选逗号分隔、未知请求类型放弃作答权。
 */
class ConsoleAnswererTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ConsoleAnswererTest —— 终端回答者：审批 y/n、EOF fail-closed、"
                + "序号与自由文本回答、多选、未知类型放弃（6 用例） ===");
    }

    /** 从脚本输入构造回答者。 */
    private ConsoleAnswerer answerer(String... scriptedLines) {
        StringBuilder input = new StringBuilder();
        for (String line : scriptedLines) {
            input.append(line).append("\n");
        }
        BufferedReader in = new BufferedReader(
                new java.io.InputStreamReader(new java.io.ByteArrayInputStream(
                        input.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        return new ConsoleAnswerer(in, new PrintStream(new java.io.ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8));
    }

    @Test
    void approvalYesAllows() {
        InteractionAnswer answer = answerer("y")
                .answer(InteractionRequest.approval("write_file", "{\"path\":\"a.txt\"}"));

        assertTrue(answer.approved());
        assertEquals("console", answer.source());
    }

    @Test
    void approvalAnythingElseDenies() {
        assertFalse(answerer("n").answer(InteractionRequest.approval("write_file", "{}")).approved());
        assertFalse(answerer("随便输入").answer(InteractionRequest.approval("write_file", "{}")).approved(),
                "y 之外的任何输入都按拒绝");
    }

    @Test
    void approvalEofFailsClosed() {
        // 脚本无输入行 → readLine 返回 null（EOF / Ctrl+C 语义）
        ConsoleAnswerer answerer = answerer();

        InteractionAnswer answer = answerer.answer(InteractionRequest.approval("write_file", "{}"));

        assertFalse(answer.approved(), "EOF 未作答按拒绝处理");
        assertEquals(InteractionAnswer.SOURCE_FAIL_CLOSED, answer.source());
    }

    @Test
    void questionOptionIndexResolvesToOptionText() {
        InteractionAnswer answer = answerer("2").answer(
                InteractionRequest.question("用哪个方案？", List.of("方案 A", "方案 B"), false));

        assertEquals(List.of("方案 B"), answer.values(), "序号解析为选项文本");
        assertTrue(answer.approved());
    }

    @Test
    void questionFreeTextAndMultiSelect() {
        InteractionAnswer free = answerer("用数据库存").answer(
                InteractionRequest.question("数据存哪？", List.of(), false));
        assertEquals(List.of("用数据库存"), free.values(), "无选项时自由文本原样采用");

        InteractionAnswer multi = answerer("1, 3").answer(
                InteractionRequest.question("带上哪些？", List.of("A", "B", "C"), true));
        assertEquals(List.of("A", "C"), multi.values(), "多选逗号分隔逐个解析为选项文本");
    }

    @Test
    void questionBlankAnswerFailsClosed() {
        ConsoleAnswerer answerer = answerer("");

        InteractionAnswer answer = answerer.answer(InteractionRequest.question("在吗？", List.of(), false));

        assertFalse(answer.approved());
        assertEquals(InteractionAnswer.SOURCE_FAIL_CLOSED, answer.source());
    }

    @Test
    void unknownKindDeclinesToAnswer() {
        InteractionRequest unknown = new InteractionRequest("未知类型", "x", "", List.of(), false);

        assertNull(answerer("y").answer(unknown), "未知请求类型放弃作答权（交下一个回答者）");
    }
}
