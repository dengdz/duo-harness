package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.tools.ApprovalDecision;
import dev.duo.harness.tools.ApprovalPolicyService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则前置裁决策略用例（M24 工单 01，ADR-0026 决策一）：命中短路（deny/allow 不
 * 打扰内层）、未命中委托内层且 presenterId 原样转发（亲和路由不回退）。
 */
class PermissionRulePolicyTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：PermissionRulePolicyTest —— 规则前置裁决：命中短路、未命中委托"
                + "与标记透传（3 用例） ===");
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

    @Test
    void denyRuleShortCircuitsWithoutConsultingInner() {
        List<InnerCall> calls = new ArrayList<>();
        PermissionRules rules = PermissionRules.load(tempDir);
        rules.setSessionRules(List.of(new PermissionRules.Rule("bash", "sudo",
                PermissionRules.Decision.DENY, PermissionRules.Scope.SESSION)));
        PermissionRulePolicy policy = new PermissionRulePolicy(rules,
                new RecordingInner(calls, ApprovalDecision.allow("test-inner")));

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
        PermissionRulePolicy policy = new PermissionRulePolicy(rules,
                new RecordingInner(calls, ApprovalDecision.deny("内层裁决", "test-inner")));

        ApprovalDecision decision = policy.decide("bash", bashArgs("npm run test"), null);
        assertEquals(ApprovalDecision.Outcome.ALLOW, decision.outcome());
        assertTrue(calls.isEmpty(), "规则放行短路——内层不被打扰");
    }

    @Test
    void missDelegatesToInnerWithPresenterIdPassedThrough() {
        List<InnerCall> calls = new ArrayList<>();
        PermissionRules rules = PermissionRules.load(tempDir);
        PermissionRulePolicy policy = new PermissionRulePolicy(rules,
                new RecordingInner(calls, ApprovalDecision.deny("内层裁决", "test-inner")));

        ApprovalDecision decision = policy.decide("bash", bashArgs("make target"), "web");
        assertEquals("test-inner", decision.policySource(), "未命中透传内层裁决");
        assertEquals(1, calls.size(), "未命中恰委托内层一次");
        assertEquals("web", calls.get(0).presenterId(), "亲和路由标记原样转发");
    }
}
