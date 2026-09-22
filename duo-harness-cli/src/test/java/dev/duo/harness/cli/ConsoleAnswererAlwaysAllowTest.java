package dev.duo.harness.cli;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;
import dev.duo.harness.tools.fs.PermissionRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批卡「总是允许」键用例（M24 工单 02，ADR-0026 决策一）：a/s 键生成带作用域的
 * allowAlways 答案（规则落盘由包装层负责）；高危根命令与规则服务缺席时 a/s 不出现
 * ——按 a/s 输入一律拒绝（fail-closed）。
 */
class ConsoleAnswererAlwaysAllowTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ConsoleAnswererAlwaysAllowTest —— 总是允许键：a/s 作用域、"
                + "高危/缺席抑制（4 用例） ===");
    }

    private static ObjectNode bashArgs(String command) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("command", command);
        return args;
    }

    private ConsoleAnswerer answerer(PermissionRules rules, String... scriptedLines) {
        StringBuilder input = new StringBuilder();
        for (String line : scriptedLines) {
            input.append(line).append("\n");
        }
        BufferedReader in = new BufferedReader(
                new java.io.InputStreamReader(new java.io.ByteArrayInputStream(
                        input.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        return new ConsoleAnswerer(() -> {
            try {
                return in.readLine();
            } catch (java.io.IOException e) {
                return null;
            }
        }, new PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8), rules);
    }

    @Test
    void alwaysKeysCarryScope() {
        PermissionRules rules = PermissionRules.load(tempDir);
        var request = InteractionRequest.approval("bash", bashArgs("npm install"), "参数摘要", null);

        InteractionAnswer project = answerer(rules, "a").answer(request);
        assertTrue(project.approved());
        assertEquals(InteractionAnswer.SCOPE_PROJECT, project.alwaysScope());

        InteractionAnswer session = answerer(rules, "s").answer(request);
        assertTrue(session.approved());
        assertEquals(InteractionAnswer.SCOPE_SESSION, session.alwaysScope());

        InteractionAnswer allow = answerer(rules, "y").answer(request);
        assertTrue(allow.approved());
        assertEquals(null, allow.alwaysScope(), "y 不携带总是允许语义");
    }

    @Test
    void highRiskCommandSuppressesAlwaysKeys() {
        PermissionRules rules = PermissionRules.load(tempDir);
        var request = InteractionRequest.approval("bash", bashArgs("sudo apt install"), "参数摘要", null);
        assertFalse(PermissionRules.alwaysAllowCandidate("bash", bashArgs("sudo apt install")),
                "高危根命令非候选（生成拦）");
        assertFalse(answerer(rules, "a").answer(request).approved(),
                "a 键被抑制后按 a 输入一律拒绝（fail-closed）");
        assertFalse(answerer(rules, "s").answer(request).approved());
    }

    @Test
    void rulesAbsentSuppressesAlwaysKeys() {
        var request = InteractionRequest.approval("bash", bashArgs("npm install"), "参数摘要", null);
        assertFalse(answerer(null, "a").answer(request).approved(), "规则服务缺席：a 按 a 输入拒绝");
        assertFalse(answerer(null, "s").answer(request).approved());
    }

    @Test
    void nonBashToolIsCandidate() {
        PermissionRules rules = PermissionRules.load(tempDir);
        var request = InteractionRequest.approval("web_fetch", null, "参数摘要", null);
        assertTrue(PermissionRules.alwaysAllowCandidate("web_fetch", request.args()),
                "非 bash 工具恒候选（工具级 allow）");
        InteractionAnswer answer = answerer(rules, "s").answer(request);
        assertTrue(answer.approved());
        assertEquals(InteractionAnswer.SCOPE_SESSION, answer.alwaysScope());
    }
}
