package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.tools.ApprovalDecision;
import dev.duo.harness.tools.ApprovalPolicyService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则与只读前置裁决策略用例（M24 工单 01/03，ADR-0026 决策一/二）：命中短路
 * （deny/allow/只读各段不扰内层）、裁决序（deny 恒优先压过只读）、未命中委托
 * 内层且 presenterId 原样转发（亲和路由不回退）。
 */
class PermissionRulePolicyTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void seedGitTrustRoot() throws Exception {
        // git 四件套只读放行依赖信任根（.git 存在性）——夹具预置
        Files.createDirectories(tempDir.resolve(".git"));
    }

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：PermissionRulePolicyTest —— 规则与只读前置裁决：命中短路、"
                + "deny 压过只读的裁决序、未命中透传（5 用例） ===");
    }

    private static ObjectNode bashArgs(String command) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("command", command);
        return args;
    }

    /** 记录内层被委托的调用（含 presenterId）——短路断言的观测点。 */
    private record InnerCall(String toolName, String presenterId) {
    }

    private record RecordingInner(List<InnerCall> calls, ApprovalDecision reply)
            implements ApprovalPolicyService {

        @Override
        public ApprovalDecision decide(String toolName, JsonNode args) {
            calls.add(new InnerCall(toolName, null));
            return reply;
        }

        @Override
        public ApprovalDecision decide(String toolName, JsonNode args, String presenterId) {
            calls.add(new InnerCall(toolName, presenterId));
            return reply;
        }
    }

    private PermissionRulePolicy policy(PermissionRules rules, List<InnerCall> calls) {
        return new PermissionRulePolicy(rules, new ReadOnlyBashDetector(tempDir),
                new RecordingInner(calls, ApprovalDecision.deny("内层裁决", "test-inner")));
    }

    @Test
    void denyRuleShortCircuitsWithoutConsultingInner() {
        List<InnerCall> calls = new ArrayList<>();
        PermissionRules rules = PermissionRules.load(tempDir);
        rules.setSessionRules(List.of(new PermissionRules.Rule("bash", "sudo",
                PermissionRules.Decision.DENY, PermissionRules.Scope.SESSION)));
        PermissionRulePolicy policy = policy(rules, calls);

        ApprovalDecision decision = policy.decide("bash", bashArgs("sudo apt install"), "cli");
        assertEquals(ApprovalDecision.Outcome.DENY, decision.outcome());
        assertEquals(PermissionRules.SOURCE, decision.policySource());
        assertTrue(calls.isEmpty(), "规则命中短路——内层（档位/交互）不被打扰");
    }

    @Test
    void allowRuleShortCircuitsWithoutConsultingInner() {
        List<InnerCall> calls = new ArrayList<>();
        PermissionRules rules = PermissionRules.load(tempDir);
        rules.setSessionRules(List.of(new PermissionRules.Rule("bash", "npm run test",
                PermissionRules.Decision.ALLOW, PermissionRules.Scope.SESSION)));
        PermissionRulePolicy policy = policy(rules, calls);

        ApprovalDecision decision = policy.decide("bash", bashArgs("npm run test"), null);
        assertEquals(ApprovalDecision.Outcome.ALLOW, decision.outcome());
        assertEquals(PermissionRules.SOURCE, decision.policySource());
        assertTrue(calls.isEmpty(), "规则放行短路——内层不被打扰");
    }

    @Test
    void denyRuleBeatsReadonlyExemption() {
        // 裁决序核心（ADR-0026 决策一/二交叉）：deny 查全部命令——只读命令 ls 命中
        // deny 规则时必须拒绝，不得被只读免审翻回（单调否决同构）
        List<InnerCall> calls = new ArrayList<>();
        PermissionRules rules = PermissionRules.load(tempDir);
        rules.setSessionRules(List.of(new PermissionRules.Rule("bash", "ls",
                PermissionRules.Decision.DENY, PermissionRules.Scope.SESSION)));
        PermissionRulePolicy policy = policy(rules, calls);

        ApprovalDecision decision = policy.decide("bash", bashArgs("ls /tmp"), "cli");
        assertEquals(ApprovalDecision.Outcome.DENY, decision.outcome());
        assertEquals(PermissionRules.SOURCE, decision.policySource());
        assertTrue(calls.isEmpty());
    }

    @Test
    void readonlyHitAllowedWithReadonlySignature() {
        // 只读免审（规则未命中时）：署名 read-only 而非 permission-rules
        List<InnerCall> calls = new ArrayList<>();
        PermissionRules rules = PermissionRules.load(tempDir);
        PermissionRulePolicy policy = policy(rules, calls);

        ApprovalDecision decision = policy.decide("bash", bashArgs("git status"), "cli");
        assertEquals(ApprovalDecision.Outcome.ALLOW, decision.outcome());
        assertEquals(ReadOnlyBashPolicy.SOURCE, decision.policySource());
        assertTrue(calls.isEmpty());
    }

    @Test
    void missDelegatesToInnerWithPresenterIdPassedThrough() {
        List<InnerCall> calls = new ArrayList<>();
        PermissionRules rules = PermissionRules.load(tempDir);
        PermissionRulePolicy policy = policy(rules, calls);

        ApprovalDecision decision = policy.decide("bash", bashArgs("make target"), "web");
        assertEquals("test-inner", decision.policySource(), "未命中透传内层裁决");
        assertEquals(1, calls.size(), "未命中恰委托内层一次");
        assertEquals("web", calls.get(0).presenterId(), "亲和路由标记原样转发");
    }
}
