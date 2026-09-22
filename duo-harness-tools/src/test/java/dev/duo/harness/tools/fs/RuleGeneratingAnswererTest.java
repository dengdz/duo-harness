package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.tools.Answerer;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「总是允许」规则生成包装层用例（M24 工单 02，ADR-0026 决策一）：a/s 语义落成
 * 项目级/会话级规则（bash 首词粒度、非 bash 工具级）、高危不生成、普通 allow 与
 * 非审批请求透传。
 */
class RuleGeneratingAnswererTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：RuleGeneratingAnswererTest —— 规则生成包装：项目/会话落盘、"
                + "高危不生成、透传（4 用例） ===");
    }

    private static ObjectNode bashArgs(String command) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("command", command);
        return args;
    }

    /** 固定返回预置答案的委托（记录 presenterId 透传观测）。 */
    private record FixedAnswerer(InteractionAnswer reply) implements Answerer {

        @Override
        public InteractionAnswer answer(InteractionRequest request) {
            return reply;
        }

        @Override
        public String presenterId() {
            return "console";
        }
    }

    @Test
    void projectScopeWritesSettingsFile() throws Exception {
        PermissionRules rules = PermissionRules.load(tempDir);
        var sink = new ArrayList<String>();
        RuleGeneratingAnswerer answerer = new RuleGeneratingAnswerer(
                new FixedAnswerer(InteractionAnswer.allowAlways("console", InteractionAnswer.SCOPE_PROJECT)),
                rules, sink::add);

        InteractionAnswer answer = answerer.answer(InteractionRequest.approval(
                "bash", bashArgs("npm run test"), "摘要", null));

        assertTrue(answer.approved());
        assertNull(answer.alwaysScope(), "上抛归一为普通 allow（审计与策略不见生成语义）");
        assertEquals("console", answer.source());
        assertEquals(1, rules.projectRules().size());
        assertEquals("npm", rules.projectRules().get(0).prefix(), "bash 首词粒度（用户裁定）");
        assertTrue(Files.readString(tempDir.resolve(".duo").resolve("settings.json")).contains("npm"),
                "项目级落 settings.json");
        assertTrue(sink.isEmpty(), "项目级不落会话事件");
        assertEquals("console", answerer.presenterId(), "亲和路由透传");
    }

    @Test
    void sessionScopeEmitsSnapshotToSink() {
        PermissionRules rules = PermissionRules.load(tempDir);
        var sink = new ArrayList<String>();
        RuleGeneratingAnswerer answerer = new RuleGeneratingAnswerer(
                new FixedAnswerer(InteractionAnswer.allowAlways("console", InteractionAnswer.SCOPE_SESSION)),
                rules, sink::add);

        answerer.answer(InteractionRequest.approval(
                "bash", bashArgs("docker compose up"), "摘要", null));

        assertEquals(1, rules.sessionRules().size());
        assertEquals("docker", rules.sessionRules().get(0).prefix());
        assertEquals(1, sink.size());
        assertTrue(sink.get(0).contains("docker"), "会话级落全量快照 JSON（permission/rules 事件载荷）");
    }

    @Test
    void highRiskDeniesAndPlainAllowPassesThrough() throws Exception {
        PermissionRules rules = PermissionRules.load(tempDir);
        var sink = new ArrayList<String>();
        RuleGeneratingAnswerer answerer = new RuleGeneratingAnswerer(
                new FixedAnswerer(InteractionAnswer.allowAlways("console", InteractionAnswer.SCOPE_PROJECT)),
                rules, sink::add);

        // 高危根命令：不生成规则且与非候选 CLI 语义对齐——按拒绝处理（fail-closed）
        InteractionAnswer sudo = answerer.answer(InteractionRequest.approval(
                "bash", bashArgs("sudo apt install"), "摘要", null));
        assertFalse(sudo.approved(), "非候选按 a/s 一律拒绝（与 CLI 对齐）");
        assertEquals("console", sudo.source());
        assertEquals(0, rules.projectRules().size(), "高危不生成规则");

        // 普通 allow：原样透传
        InteractionAnswer plain = new RuleGeneratingAnswerer(
                new FixedAnswerer(InteractionAnswer.allow("console")), rules, sink::add)
                .answer(InteractionRequest.approval("bash", bashArgs("npm run build"), "摘要", null));
        assertNull(plain.alwaysScope());
        assertTrue(plain.approved());
        assertEquals(0, rules.projectRules().size(), "普通 allow 不生成");
        assertTrue(Files.notExists(tempDir.resolve(".duo").resolve("settings.json")),
                "无生成动作不落文件");
    }

    @Test
    void nonBashToolGeneratesToolLevelRule() {
        PermissionRules rules = PermissionRules.load(tempDir);
        RuleGeneratingAnswerer answerer = new RuleGeneratingAnswerer(
                new FixedAnswerer(InteractionAnswer.allowAlways("web", InteractionAnswer.SCOPE_SESSION)),
                rules, null);

        answerer.answer(InteractionRequest.approval("web_fetch", null, "摘要", "web"));

        assertEquals(1, rules.sessionRules().size());
        assertNull(rules.sessionRules().get(0).prefix(), "非 bash 为工具级规则（无前缀）");
    }
}
