package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.events.WaterfallListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 B（工具服务级）用例：审批策略——pre-execute 三态（allow / deny / ask）的
 * ask 声明与裁决分离、两个预设策略的行为差异、审批拒绝的错误结果含策略来源、
 * 未解析的 ask 按"未配置即拒"处理、决策日志可观察、yml 配置端到端。
 */
class ApprovalPolicyTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ApprovalPolicyTest —— 审批策略：always-deny 拒绝且点名策略、"
                + "auto-approve 白名单内外行为差异、ask 委托策略裁决、未配置即拒、"
                + "未被声明不介入、MCP 工具同路径、决策日志可观察、yml 配置端到端（9 用例） ===");
    }

    /** 服务视图接口（方法名即服务名）。 */
    interface ToolsView {

        ToolsService tools();
    }

    private Context root;

    @BeforeEach
    void setUp() {
        root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
    }

    @AfterEach
    void tearDown() {
        root.dispose();
    }

    private ToolsService tools() {
        return root.as(ToolsView.class).tools();
    }

    /** 最小回声工具；{@code needsApproval} 打开工具自身的 ask 声明。 */
    private static ToolDefinition echoTool(String name, boolean needsApproval) {
        AtomicInteger count = new AtomicInteger();
        return new ToolDefinition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "回声工具";
            }

            @Override
            public JsonNode parameters() {
                return NullNode.getInstance();
            }

            @Override
            public boolean requiresApproval() {
                return needsApproval;
            }

            @Override
            public Object execute(ToolExecution execution) {
                count.incrementAndGet();
                return name + ":ok";
            }
        };
    }

    private ToolDefinition registerEcho(String name) {
        return registerEcho(name, false);
    }

    private ToolDefinition registerEcho(String name, boolean needsApproval) {
        ToolDefinition tool = echoTool(name, needsApproval);
        tools().register(root, tool);
        return tool;
    }

    private void mountApprovalPlugin(JsonNode config) {
        root.plugin(new ApprovalPlugin(), config).awaitStartup();
    }

    /** 治理监听器：对指定工具声明 ask（不裁决，把裁决权交给策略服务）。 */
    private void mountAskDeclarer(String toolName) {
        root.on(ToolsService.PRE_EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    if (toolName.equals(exec.toolName())) {
                        exec.requestApproval();
                    }
                    return next.invoke(exec);
                });
    }

    // === 用例 ===

    @Test
    void alwaysDenyRejectsAndNamesPolicySource() {
        mountApprovalPlugin(config("always-deny", null));
        registerEcho("echo", true);

        ToolResult result = tools().execute("echo", NullNode.getInstance());

        assertTrue(result.isError(), String.valueOf(result.value()));
        String msg = String.valueOf(result.value());
        assertTrue(msg.contains("被审批策略拒绝"), msg);
        assertTrue(msg.contains("always-deny"), "应点名策略来源: " + msg);
    }

    @Test
    void autoApproveWhitelistsAndDeniesOutsiders() {
        mountApprovalPlugin(config("auto-approve", List.of("echo")));
        registerEcho("echo", true);
        registerEcho("boom", true);

        ToolResult allowed = tools().execute("echo", NullNode.getInstance());
        assertFalse(allowed.isError(), "白名单内应放行: " + allowed.value());
        assertEquals("echo:ok", allowed.value());

        ToolResult denied = tools().execute("boom", NullNode.getInstance());
        assertTrue(denied.isError(), "白名单外应拒绝");
        String msg = String.valueOf(denied.value());
        assertTrue(msg.contains("不在审批白名单"), msg);
        assertTrue(msg.contains("auto-approve"), "应点名策略来源: " + msg);
    }

    @Test
    void undeclaredCallIsNotGoverned() {
        // 未被声明需审批的调用不进入审批：策略在场也不介入
        mountApprovalPlugin(config("always-deny", null));
        registerEcho("plain");

        ToolResult result = tools().execute("plain", NullNode.getInstance());

        assertFalse(result.isError(), "未声明 ask 的调用不该被审批拒绝: " + result.value());
    }

    @Test
    void toolDeclaredAskIsResolvedByPolicy() {
        mountApprovalPlugin(config("auto-approve", List.of("danger")));
        registerEcho("danger", true);

        ToolResult result = tools().execute("danger", NullNode.getInstance());

        assertFalse(result.isError(), "策略放行的 ask 应通过: " + result.value());
        assertEquals("danger:ok", result.value());
    }

    @Test
    void listenerDeclaredAskWithoutPolicyDefaultsDeny() {
        // 无审批插件：治理监听器声明 ask 但无人解析 → "未配置即拒"
        registerEcho("echo");
        mountAskDeclarer("echo");

        ToolResult result = tools().execute("echo", NullNode.getInstance());

        assertTrue(result.isError(), "未配置策略时 ask 应默认拒绝");
        String msg = String.valueOf(result.value());
        assertTrue(msg.contains("审批策略未配置"), msg);
        assertTrue(msg.contains(ApprovalPolicyService.SOURCE_UNCONFIGURED), "策略来源应为 none: " + msg);
    }

    @Test
    void approvalAppliesToMcpToolName() {
        // MCP 工具名（mcp__<server>__<tool>）与本地工具走同一审批路径
        mountApprovalPlugin(config("always-deny", null));
        registerEcho("mcp__test__ping", true);

        ToolResult result = tools().execute("mcp__test__ping", NullNode.getInstance());

        assertTrue(result.isError(), "MCP 工具名走同一审批路径");
        String msg = String.valueOf(result.value());
        assertTrue(msg.contains("被审批策略拒绝"), msg);
        assertTrue(msg.contains("always-deny"), "策略来源: " + msg);
    }

    @Test
    void directDenyShortCircuitsApproval() {
        mountApprovalPlugin(config("auto-approve", List.of("echo")));
        registerEcho("echo", true);
        root.on(ToolsService.PRE_EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    exec.deny("直接否决");
                    return false;
                });

        ToolResult result = tools().execute("echo", NullNode.getInstance());

        assertTrue(result.isError());
        String msg = String.valueOf(result.value());
        assertTrue(msg.contains("直接否决"), msg);
        assertFalse(msg.contains("auto-approve"), "直接 deny 不应触发审批裁决: " + msg);
        assertFalse(msg.contains("被审批策略拒绝"), "直接 deny 不经策略: " + msg);
    }

    @Test
    void decisionIsLoggedForAudit() {
        mountApprovalPlugin(config("auto-approve", List.of("echo")));
        registerEcho("echo", true);
        registerEcho("boom", true);

        String logged = captureStderr(() -> {
            tools().execute("echo", NullNode.getInstance());
            tools().execute("boom", NullNode.getInstance());
        });

        // 安全审计：决策结果 + 策略来源 + 工具名三者齐备
        assertTrue(logged.contains("审批决策"), "应有决策日志: " + logged);
        assertTrue(logged.contains("工具=echo") && logged.contains("结果=ALLOW")
                && logged.contains("策略=auto-approve"), "放行决策日志: " + logged);
        assertTrue(logged.contains("工具=boom") && logged.contains("结果=DENY"),
                "拒绝决策日志: " + logged);
    }

    @Test
    void policyIsPublishedAndConfigurableByYml(@TempDir Path tempDir) throws Exception {
        Path yml = tempDir.resolve("approval.yml");
        Files.writeString(yml, """
                plugins:
                  - id: tools
                    name: dev.duo.harness.tools.ToolsPlugin
                  - id: approval
                    name: dev.duo.harness.tools.ApprovalPlugin
                    config:
                      policy: auto-approve
                      allowedTools:
                        - echo
                  - id: echo
                    name: dev.duo.harness.tools.ApprovalPolicyTest$EchoToolPlugin
                """, StandardCharsets.UTF_8);

        Context booted = dev.duo.harness.core.api.boot.Boot.from(yml);

        // 策略经视图可寻址（服务形式发布）
        ApprovalPolicyService policy = booted.as(ApprovalPolicyView.class).approval();
        assertNotNull(policy, "策略服务应已发布");
        assertEquals(ApprovalDecision.Outcome.DENY,
                policy.decide("other", NullNode.getInstance()).outcome(), "白名单外应拒绝");

        // yml 配置端到端：白名单内的声明工具放行
        ToolsService bootedTools = booted.as(ToolsView.class).tools();
        ToolResult result = bootedTools.execute("echo", NullNode.getInstance());
        assertFalse(result.isError(), "yml 配的白名单应放行: " + result.value());

        booted.dispose();
    }

    /** yml 装载用的工具插件（公共无参构造 + 自身声明需审批）。 */
    public static final class EchoToolPlugin implements Plugin<Void> {

        /** 服务视图接口（方法名即服务名）。 */
        interface View {

            ToolsService tools();
        }

        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public java.util.Set<String> inject() {
            return java.util.Set.of(ToolsService.SERVICE_NAME);
        }

        @Override
        public dev.duo.harness.core.api.Disposable apply(Context ctx, Void config) {
            ctx.as(View.class).tools().register(ctx, echoTool("echo", true));
            return null;
        }
    }

    // === 夹具 ===

    private static JsonNode config(String policy, List<String> allowedTools) {
        var node = JsonNodeFactory.instance.objectNode();
        node.put("policy", policy);
        if (allowedTools != null) {
            var arr = node.putArray("allowedTools");
            allowedTools.forEach(arr::add);
        }
        return node;
    }

    /** 捕获标准错误（slf4j-simple 的输出目标）期间的输出。 */
    private static String captureStderr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
