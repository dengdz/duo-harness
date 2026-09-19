package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.tools.ApprovalDecision;
import dev.duo.harness.tools.ApprovalPolicyService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 档位闸门映射用例（BUG-20260915-01）：ALLOW 短路（内层策略不被打扰）与
 * ASK 委托（内层裁决透传）全组合——write/edit 按路径、bash/未知工具保守 ask、
 * path 参数缺失保守 ask。
 */
class WorkspaceGatePolicyTest {

    @TempDir
    Path tempDir;

    private WorkspacePolicy workspace;
    /** 记录内层策略被委托的调用——短路断言的观测点。 */
    private List<String> innerCalls;
    private ApprovalPolicyService inner;

    private WorkspaceGatePolicy gate(WorkspacePolicy.Mode mode) {
        workspace = new WorkspacePolicy(tempDir, mode);
        innerCalls = new ArrayList<>();
        inner = new ApprovalPolicyService() {
            @Override
            public ApprovalDecision decide(String toolName, com.fasterxml.jackson.databind.JsonNode args) {
                innerCalls.add(toolName);
                return ApprovalDecision.deny("内层裁决", "test-inner");
            }
        };
        return new WorkspaceGatePolicy(inner, workspace);
    }

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WorkspaceGatePolicyTest —— 档位闸门映射："
                + "ALLOW 短路 / ASK 委托全组合、三参透传（7 用例） ===");
    }

        @Test
    void threeArgDecidePassesPresenterIdToInnerOnAskOnly() {
        // OCR #10 回归：三参入口是亲和路由的载体——ASK 委托时 presenterId 原样透传，
        // ALLOW 短路与标记无关（不咨询内层）。内层用三参覆写观测收到的标记。
        WorkspaceGatePolicy policy = gate(WorkspacePolicy.Mode.WORKSPACE_WRITE);
        List<String> seen = new ArrayList<>();
        var recordingInner = new ApprovalPolicyService() {
            @Override
            public ApprovalDecision decide(String toolName, com.fasterxml.jackson.databind.JsonNode args) {
                return ApprovalDecision.allow("inner");
            }

            @Override
            public ApprovalDecision decide(String toolName,
                                           com.fasterxml.jackson.databind.JsonNode args,
                                           String presenterId) {
                seen.add(toolName + ":" + presenterId);
                return ApprovalDecision.allow("inner");
            }
        };
        policy = new WorkspaceGatePolicy(recordingInner, workspace);

        // ASK 路径（workspace-write 档越界写）：presenterId 原样透传内层
        var outsideArgs = JsonNodeFactory.instance.objectNode()
                .put("path", tempDir.getParent().resolve("档外-ocr10.txt").toAbsolutePath().toString());
        policy.decide("write", outsideArgs, "cli");
        assertEquals(1, seen.size(), "恰委托一次");
        assertEquals("write:cli", seen.get(0), "发起方标记原样透传");

        // ALLOW 短路：档内路径不咨询内层（与标记无关）
        var inArgs = JsonNodeFactory.instance.objectNode().put("path", "in-workspace.txt");
        policy.decide("write", inArgs, "web");
        assertEquals(1, seen.size(), "ALLOW 短路不咨询内层");
    }

    @Test
    void workspaceWriteAllowsContainedWriteAndDelegatesOutside() throws Exception {
        WorkspaceGatePolicy gate = gate(WorkspacePolicy.Mode.WORKSPACE_WRITE);
        Path outside = Files.createTempFile("duo-gate-out", ".txt");
        assertEquals(ApprovalDecision.allow("workspace"), gate.decide("write",
                JsonNodeFactory.instance.objectNode().put("path", "inside.txt")), "区内写短路放行");
        assertEquals(ApprovalDecision.deny("内层裁决", "test-inner"), gate.decide("write",
                JsonNodeFactory.instance.objectNode().put("path", outside.toString())), "越界写委托内层");
        assertEquals(List.of("write"), innerCalls, "仅越界写进入内层");
    }

    @Test
    void readOnlyDelegatesAllWrites() {
        WorkspaceGatePolicy gate = gate(WorkspacePolicy.Mode.READ_ONLY);
        assertEquals(ApprovalDecision.deny("内层裁决", "test-inner"),
                gate.decide("edit", JsonNodeFactory.instance.objectNode().put("path", "a.txt")));
        assertEquals(List.of("edit"), innerCalls, "read-only 档写一律 ask——交内层裁决");
    }

    @Test
    void bashAlwaysDelegatesOutsideDanger() {
        WorkspaceGatePolicy gate = gate(WorkspacePolicy.Mode.WORKSPACE_WRITE);
        gate.decide("bash", JsonNodeFactory.instance.objectNode());
        assertEquals(List.of("bash"), innerCalls, "bash 非 danger 档一律 ask——交内层裁决");
    }

    @Test
    void unknownToolAndMissingPathDelegate() {
        WorkspaceGatePolicy gate = gate(WorkspacePolicy.Mode.WORKSPACE_WRITE);
        gate.decide("guarded_write", JsonNodeFactory.instance.objectNode());
        gate.decide("write", JsonNodeFactory.instance.objectNode()); // 无 path → 保守 ask
        assertEquals(List.of("guarded_write", "write"), innerCalls, "未知工具与 path 缺失保守委托");
    }

    @Test
    void nullArgsDelegateSafely() {
        WorkspaceGatePolicy gate = gate(WorkspacePolicy.Mode.WORKSPACE_WRITE);
        assertEquals(ApprovalDecision.deny("内层裁决", "test-inner"), gate.decide("write", null));
        assertEquals(List.of("write"), innerCalls, "args 缺失保守 ask 不误放");
    }
}
