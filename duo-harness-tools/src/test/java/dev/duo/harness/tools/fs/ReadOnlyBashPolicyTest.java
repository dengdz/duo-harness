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
 * 只读免审批策略用例（M24 工单 03，ADR-0026 决策二）：命中放行署名 read-only、
 * 未命中委托内层且 presenterId 原样转发（亲和路由不回退）。
 */
class ReadOnlyBashPolicyTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void seedGitTrustRoot() throws Exception {
        // git 四件套只读放行依赖信任根（.git 存在性）——夹具预置
        Files.createDirectories(tempDir.resolve(".git"));
    }

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ReadOnlyBashPolicyTest —— 只读免审策略：命中署名、未命中透传（2 用例） ===");
    }

    private static ObjectNode bashArgs(String command) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("command", command);
        return args;
    }

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
    void readonlyHitAllowsWithoutConsultingInner() {
        List<InnerCall> calls = new ArrayList<>();
        ReadOnlyBashPolicy policy = new ReadOnlyBashPolicy(new ReadOnlyBashDetector(tempDir),
                new RecordingInner(calls, ApprovalDecision.deny("内层裁决", "test-inner")));

        ApprovalDecision decision = policy.decide("bash", bashArgs("git status"), "cli");
        assertEquals(ApprovalDecision.Outcome.ALLOW, decision.outcome());
        assertEquals(ReadOnlyBashPolicy.SOURCE, decision.policySource());
        assertTrue(calls.isEmpty(), "只读命中短路——内层（档位/交互）不被打扰");

        ApprovalDecision write = policy.decide("bash", bashArgs("rm -rf /"), "cli");
        assertEquals(ApprovalDecision.Outcome.DENY, write.outcome(), "非只读委托内层");
        assertEquals(1, calls.size());
        assertEquals("cli", calls.get(0).presenterId(), "亲和标记原样转发");
    }
}
