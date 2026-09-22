package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.tools.ApprovalDecision;
import dev.duo.harness.tools.ApprovalPolicyService;
import dev.duo.harness.tools.InteractionPlugin;
import dev.duo.harness.tools.ToolsPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 审批插件装配接线用例（M24 工单 03，ADR-0026 决策二）：真实插件树（tools + fs +
 * answers + approval，规则服务有/无两分支）下的裁决面——只读 bash 免审放行（署名
 * read-only）、非只读 bash 不被免审（走档位 ask → 无人应答 fail-closed deny）。
 * 夹具隔离：fs root 指向带 .git 的临时目录（信任分类确定性）、规则服务由内联插件
 * 提供（不读仓库/用户真实规则文件）。
 */
class WorkspaceApprovalPluginAssemblyTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WorkspaceApprovalPluginAssemblyTest —— 审批装配接线：规则有/无两分支的"
                + "只读免审与非只读不豁免（2 用例） ===");
    }

    /** 权限规则服务的视图接口（方法名即服务名 permissionRules）。 */
    interface PermissionRulesView {

        PermissionRules permissionRules();
    }

    /** 审批策略服务的视图接口（方法名即服务名 approval）。 */
    interface ApprovalView {

        ApprovalPolicyService approval();
    }

    private record Mounted(Context root, ApprovalPolicyService policy) {
    }

    private static ObjectNode bashArgs(String command) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("command", command);
        return args;
    }

    /** 挂载 tools + fs + answers + approval（withRules 决定是否挂 permission-rules 行）。 */
    private Mounted mount(boolean withRules) throws Exception {
        // fs root 指向带 .git 的临时目录：git 四件套信任分类确定性 + 与仓库真实文件隔离
        Path gitRoot = tempDir.resolve("proj");
        Files.createDirectories(gitRoot.resolve(".git"));
        Context root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        root.plugin(new FsToolsPlugin(),
                JsonNodeFactory.instance.objectNode()
                        .put("mode", "workspace-write")
                        .put("root", gitRoot.toString())).awaitStartup();
        root.plugin(new InteractionPlugin(), null).awaitStartup();
        if (withRules) {
            // 内联插件发布规则服务（项目根同指临时目录，不读真实 settings.json）
            AtomicReference<PermissionRules> ref = new AtomicReference<>();
            root.plugin(new Plugin<Void>() {
                @Override
                public java.util.Set<String> inject() {
                    return java.util.Set.of();
                }

                @Override
                public Class<Void> configType() {
                    return null;
                }

                @Override
                public Disposable apply(Context ctx, Void config) {
                    PermissionRules rules = PermissionRules.load(gitRoot);
                    ref.set(rules);
                    return ctx.provide(PermissionRules.SERVICE_NAME, rules);
                }
            }, null).awaitStartup();
        }
        root.plugin(new WorkspaceApprovalPlugin(), null).awaitStartup();
        return new Mounted(root, root.as(ApprovalView.class).approval());
    }

    @Test
    void readonlyBashExemptedInBothAssemblyVariants() throws Exception {
        // 规则在场：deny → 只读 → allow → 档位；只读命中在 ② 段放行
        Mounted mounted = mount(true);
        try {
            ApprovalDecision decision = mounted.policy().decide("bash", bashArgs("git status"), null);
            assertEquals(ApprovalDecision.Outcome.ALLOW, decision.outcome());
            assertEquals(ReadOnlyBashPolicy.SOURCE, decision.policySource());
        } finally {
            mounted.root().dispose();
        }

        // 规则缺席：ReadOnlyBashPolicy 直包档位闸门，只读免审独立生效
        Mounted bare = mount(false);
        try {
            ApprovalDecision bareDecision = bare.policy().decide("bash", bashArgs("ls /tmp"), null);
            assertEquals(ApprovalDecision.Outcome.ALLOW, bareDecision.outcome());
            assertEquals(ReadOnlyBashPolicy.SOURCE, bareDecision.policySource());
        } finally {
            bare.root().dispose();
        }
    }

    @Test
    void nonReadonlyBashNotExempted() throws Exception {
        // 非只读 bash 不被免审：走到档位 ask → 交互服务在场但无回答者 → fail-closed deny
        Mounted mounted = mount(true);
        try {
            ApprovalDecision decision = mounted.policy().decide("bash", bashArgs("rm -rf /tmp/x"), null);
            assertEquals(ApprovalDecision.Outcome.DENY, decision.outcome());
            assertNotEquals(ReadOnlyBashPolicy.SOURCE, decision.policySource(), "非只读不得署名只读放行");

            // 复合结构（管道）fail-closed：即便首词只读也不免审
            ApprovalDecision piped = mounted.policy().decide("bash", bashArgs("ls | wc -l"), null);
            assertNotEquals(ApprovalDecision.Outcome.ALLOW, piped.outcome(), "管道复合结构照常审批");
        } finally {
            mounted.root().dispose();
        }
    }
}
