package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.tools.ApprovalDecision;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限规则用例（M24 工单 01，ADR-0026 决策一）：词边界前缀匹配矩阵、deny 恒优先
 * 裁决序、项目级 settings.json 读写（坏文件降级/其他键保留）、会话级 JSON 往返与
 * 宽容解析、项目根发现（.git 标定）。
 */
class PermissionRulesTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：PermissionRulesTest —— 权限规则：词边界匹配矩阵、deny 恒优先、"
                + "settings.json 读写降级、会话 JSON 往返、项目根发现（11 用例） ===");
    }

    private static ObjectNode bashArgs(String command) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("command", command);
        return args;
    }

    private static PermissionRules.Rule rule(String tool, String prefix, PermissionRules.Decision decision) {
        return new PermissionRules.Rule(tool, prefix, decision, PermissionRules.Scope.SESSION);
    }

    @Test
    void wordBoundaryPrefixMatching() {
        // 词边界：命令与前缀全等，或前缀后紧跟空白——短前缀吞不掉长命令名（grill Q17）
        assertTrue(PermissionRules.prefixMatches("ls", "ls"));
        assertTrue(PermissionRules.prefixMatches("ls", "ls -la /tmp"));
        assertTrue(PermissionRules.prefixMatches("npm run test", "npm run test"));
        assertTrue(PermissionRules.prefixMatches("npm run test", "npm run test --watch"));
        assertFalse(PermissionRules.prefixMatches("ls", "lsof -i"));
        assertFalse(PermissionRules.prefixMatches("ls", "lsblk"));
        assertFalse(PermissionRules.prefixMatches("npm run test", "npm run testcase"));
    }

    @Test
    void denySegmentWinsWhenBothSegmentsMatch() {
        // deny 段恒优先（查全部命令，guard 单调否决同构）：deny/allow 同时命中各段都有解，
        // 裁决序由策略层先取 deny 段（PermissionRulePolicyTest 锁定顺序）
        PermissionRules rules = PermissionRules.load(tempDir);
        rules.setSessionRules(List.of(
                rule("bash", "docker logs", PermissionRules.Decision.ALLOW),
                rule("bash", "docker", PermissionRules.Decision.DENY)));
        Optional<ApprovalDecision> deny = rules.denyVerdict("bash", bashArgs("docker logs -f web"));
        assertTrue(deny.isPresent());
        assertEquals(ApprovalDecision.Outcome.DENY, deny.get().outcome());
        assertEquals(PermissionRules.SOURCE, deny.get().policySource());
        assertTrue(rules.allowVerdict("bash", bashArgs("docker logs -f web")).isPresent(),
                "allow 段独立可用（策略层先取 deny 段）");
    }

    @Test
    void allowSegmentShortCircuitsWithSignature() {
        PermissionRules rules = PermissionRules.load(tempDir);
        rules.setSessionRules(List.of(rule("bash", "npm run test", PermissionRules.Decision.ALLOW)));
        Optional<ApprovalDecision> verdict = rules.allowVerdict("bash", bashArgs("npm run test --watch"));
        assertTrue(verdict.isPresent());
        assertEquals(ApprovalDecision.Outcome.ALLOW, verdict.get().outcome());
        assertEquals(PermissionRules.SOURCE, verdict.get().policySource());
        assertEquals("", verdict.get().reason());
    }

    @Test
    void denyReasonCarriesRuleDescription() {
        PermissionRules rules = PermissionRules.load(tempDir);
        rules.setSessionRules(List.of(rule("bash", "sudo", PermissionRules.Decision.DENY)));
        Optional<ApprovalDecision> verdict = rules.denyVerdict("bash", bashArgs("sudo apt install"));
        assertTrue(verdict.isPresent());
        assertTrue(verdict.get().reason().contains("权限规则拒绝"), verdict.get().reason());
        assertTrue(verdict.get().reason().contains("deny bash sudo*"), verdict.get().reason());
    }

    @Test
    void toolLevelRuleMatchesAnyArgsOtherToolsUntouched() {
        // 无前缀 = 工具级规则（任意参数）；工具名不匹配不命中；bash 前缀规则不外溢到其他工具
        PermissionRules rules = PermissionRules.load(tempDir);
        rules.setSessionRules(List.of(
                new PermissionRules.Rule("web_fetch", null, PermissionRules.Decision.DENY,
                        PermissionRules.Scope.SESSION),
                rule("bash", "npm run test", PermissionRules.Decision.ALLOW)));
        assertTrue(rules.denyVerdict("web_fetch", bashArgs("https://example.com")).isPresent(),
                "工具级规则对任意参数命中");
        assertTrue(rules.allowVerdict("bash", bashArgs("npm run test")).isPresent(), "bash 命中自身规则");
        assertTrue(rules.allowVerdict("read", bashArgs("sudo rm -rf /")).isEmpty(),
                "bash 前缀规则不命中其他工具（read 无规则）");
    }

    @Test
    void bashPrefixRuleIgnoresMissingCommandArg() {
        PermissionRules rules = PermissionRules.load(tempDir);
        rules.setSessionRules(List.of(rule("bash", "ls", PermissionRules.Decision.ALLOW)));
        assertTrue(rules.allowVerdict("bash", JsonNodeFactory.instance.objectNode()).isEmpty(),
                "无 command 参数的前缀规则不命中（fail-closed）");
        assertTrue(rules.denyVerdict("bash", JsonNodeFactory.instance.objectNode()).isEmpty());
    }

    @Test
    void loadMissingFileMeansNoRules() {
        PermissionRules rules = PermissionRules.load(tempDir.resolve("absent"));
        assertTrue(rules.projectRules().isEmpty());
        assertTrue(rules.allowVerdict("bash", bashArgs("anything")).isEmpty());
        assertTrue(rules.denyVerdict("bash", bashArgs("anything")).isEmpty());
    }

    @Test
    void loadReadsPermissionsSectionPreservingScope() throws Exception {
        Path file = tempDir.resolve(".duo").resolve("settings.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"other": {"keep": true}, "permissions": {"rules": [
                  {"tool": "bash", "prefix": "sudo", "decision": "deny"},
                  {"tool": "web_fetch", "decision": "allow"}
                ]}}
                """);
        PermissionRules rules = PermissionRules.load(tempDir);
        assertEquals(2, rules.projectRules().size());
        assertTrue(rules.denyVerdict("bash", bashArgs("sudo ls")).isPresent());
        assertTrue(rules.allowVerdict("web_fetch", bashArgs("https://example.com")).isPresent());
        assertTrue(rules.allowVerdict("bash", bashArgs("curl example.com")).isEmpty(), "未命中交内层链");
        assertTrue(rules.denyVerdict("bash", bashArgs("curl example.com")).isEmpty(), "未命中交内层链");
    }

    @Test
    void corruptSettingsDegradesToNoRulesWithoutThrowing() throws Exception {
        Path file = tempDir.resolve(".duo").resolve("settings.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "不是 JSON{");
        PermissionRules rules = PermissionRules.load(tempDir);
        assertTrue(rules.projectRules().isEmpty(), "坏文件降级为无规则（读侧宽容不崩）");
    }

    @Test
    void removeProjectRuleRewritesPreservingOtherKeys() throws Exception {
        Path file = tempDir.resolve(".duo").resolve("settings.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"other": {"keep": true}, "permissions": {"rules": [
                  {"tool": "bash", "prefix": "sudo", "decision": "deny"},
                  {"tool": "bash", "prefix": "mkfs", "decision": "deny"}
                ]}}
                """);
        PermissionRules rules = PermissionRules.load(tempDir);
        rules.removeProjectRule(1);
        assertEquals(1, rules.projectRules().size(), "内存表同步更新");
        String rewritten = Files.readString(file);
        assertTrue(rewritten.contains("\"other\""), "重写保留文件内其他键");
        assertTrue(rewritten.contains("mkfs"), "剩余规则仍在");
        assertFalse(rewritten.contains("\"sudo\""), "被删规则不在");
        assertThrows(IllegalArgumentException.class, () -> rules.removeProjectRule(9), "越界编号明确报错");
    }

    @Test
    void sessionRulesJsonRoundTripAndTolerantParse() {
        var original = List.of(
                rule("bash", "docker logs", PermissionRules.Decision.ALLOW),
                new PermissionRules.Rule("web_fetch", null, PermissionRules.Decision.DENY,
                        PermissionRules.Scope.SESSION));
        String json = PermissionRules.rulesToJson(original);
        List<PermissionRules.Rule> parsed = PermissionRules.parseRulesJson(
                json, PermissionRules.Scope.SESSION);
        assertEquals(original, parsed, "JSON 往返一致");

        assertTrue(PermissionRules.parseRulesJson(null, PermissionRules.Scope.SESSION).isEmpty());
        assertTrue(PermissionRules.parseRulesJson("", PermissionRules.Scope.SESSION).isEmpty());
        assertTrue(PermissionRules.parseRulesJson("garbage{", PermissionRules.Scope.SESSION).isEmpty(),
                "坏 JSON 按空规则（恢复是尽力而为）");
        assertTrue(PermissionRules.parseRulesJson(
                "[{\"tool\":\"bash\",\"decision\":\"weird\"}]", PermissionRules.Scope.SESSION).isEmpty(),
                "非法决策值条目跳过");
    }

    @Test
    void findProjectRootWalksToGitMarker() throws Exception {
        Path project = tempDir.resolve("proj");
        Files.createDirectories(project.resolve(".git").resolve("objects"));
        Path nested = project.resolve("a").resolve("b");
        Files.createDirectories(nested);
        assertEquals(project, PermissionRules.findProjectRoot(nested), "向上走到 .git 标记");
        Path plain = tempDir.resolve("plain");
        Files.createDirectories(plain);
        assertEquals(plain.toAbsolutePath(), PermissionRules.findProjectRoot(plain),
                "无标记即起点自身");
    }

    @Test
    void highRiskRootBlocksAllowRulesAtRuntime() {
        // 高危双拦之运行时拦（M24 工单 02，ADR-0026 决策一）：手写 allow 规则对高危
        // 命令不生效，命中走 ask/档位
        PermissionRules rules = PermissionRules.load(tempDir);
        rules.setSessionRules(List.of(rule("bash", "sudo", PermissionRules.Decision.ALLOW)));
        assertTrue(rules.allowVerdict("bash", bashArgs("sudo apt install")).isEmpty(),
                "高危根命令 allow 规则运行时不生效");
        assertTrue(rules.denyVerdict("bash", bashArgs("sudo apt install")).isEmpty());
        assertTrue(rules.allowVerdict("bash", bashArgs("npm install")).isEmpty(), "非高危不受影响");
    }

    @Test
    void alwaysAllowCandidateRespectsHighRiskAndBareName() {
        // 高危双拦之生成拦：高危/路径前缀命令不候选；非 bash 恒候选
        assertFalse(PermissionRules.alwaysAllowCandidate("bash", bashArgs("sudo apt install")));
        assertFalse(PermissionRules.alwaysAllowCandidate("bash", bashArgs("/usr/bin/sudo ls")),
                "路径前缀不识别（不候选）");
        assertTrue(PermissionRules.alwaysAllowCandidate("bash", bashArgs("npm install")));
        assertTrue(PermissionRules.alwaysAllowCandidate("web_fetch", JsonNodeFactory.instance.objectNode()),
                "非 bash 工具恒候选");
        assertFalse(PermissionRules.alwaysAllowCandidate("bash", JsonNodeFactory.instance.objectNode()),
                "bash 无 command 参数不候选");
    }

    @Test
    void allowRuleForUsesFirstWordGranularity() {
        // 前缀粒度=首词（用户裁定，grill Q-02-1）：npm run test --watch → npm
        PermissionRules.Rule bash = PermissionRules.allowRuleFor(
                "bash", bashArgs("npm run test --watch"), PermissionRules.Scope.PROJECT);
        assertEquals("npm", bash.prefix());
        assertEquals(PermissionRules.Scope.PROJECT, bash.scope());
        assertNull(PermissionRules.allowRuleFor(
                "bash", bashArgs("sudo rm"), PermissionRules.Scope.PROJECT), "高危不生成");
        PermissionRules.Rule toolLevel = PermissionRules.allowRuleFor(
                "web_fetch", JsonNodeFactory.instance.objectNode(), PermissionRules.Scope.SESSION);
        assertNull(toolLevel.prefix(), "非 bash 为工具级规则");
        assertEquals(PermissionRules.Scope.SESSION, toolLevel.scope());
    }

    @Test
    void addProjectAndSessionRulesPersist() throws Exception {
        PermissionRules rules = PermissionRules.load(tempDir);
        rules.addProjectRule(rule("bash", "docker", PermissionRules.Decision.ALLOW));
        assertEquals(1, rules.projectRules().size());
        assertTrue(Files.readString(tempDir.resolve(".duo").resolve("settings.json")).contains("docker"),
                "项目级追加重写 settings.json");
        String snapshot = rules.addSessionRule(rule("bash", "kubectl", PermissionRules.Decision.ALLOW));
        assertTrue(snapshot.contains("kubectl"));
        assertEquals(1, rules.sessionRules().size());
    }
}
