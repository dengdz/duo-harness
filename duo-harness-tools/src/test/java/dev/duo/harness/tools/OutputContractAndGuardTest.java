package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.events.WaterfallListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 B（工具服务级）用例：output 契约与 guard 单调否决——
 * 契约违约点名、未声明宽松透传；guard 理由拒绝/null 放行、顺序短路、
 * 拒绝不可翻回、时机在审批之后、随注册作用域销毁失效。
 */
class OutputContractAndGuardTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：OutputContractAndGuardTest —— output 契约：违约点名/合规放行/未声明透传；"
                + "guard：理由拒绝/null 放行/顺序短路/拒绝不可翻回/时机在审批后/随作用域失效（10 用例） ===");
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

    /** 返回固定值的工具；output 为其输出契约（null = 未声明）。 */
    private static ToolDefinition typedTool(String name, Object returns, JsonNode output) {
        return typedTool(name, returns, output, new AtomicInteger());
    }

    /** 同上，但本体执行计数写入外部 counter（供"本体是否执行"断言）。 */
    private static ToolDefinition typedTool(String name, Object returns, JsonNode output,
                                            AtomicInteger executions) {
        return new ToolDefinition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "定值工具";
            }

            @Override
            public JsonNode parameters() {
                return NullNode.getInstance();
            }

            @Override
            public JsonNode output() {
                return output;
            }

            @Override
            public Object execute(ToolExecution execution) {
                executions.incrementAndGet();
                return returns;
            }
        };
    }

    private static JsonNode objectSchemaRequiring(String field, String type) {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("type", "object");
        root.putObject("properties").putObject(field).put("type", type);
        return root;
    }

    // === output 契约 ===

    @Test
    void contractViolationBecomesNamedError() {
        // 工具返回 {"value": 42}，契约要求 value 为 string → 违约点名
        JsonNode bad = JsonNodeFactory.instance.objectNode().put("value", 42);
        tools().register(root, typedTool("typed", bad, objectSchemaRequiring("value", "string")));

        ToolResult result = tools().execute("typed", NullNode.getInstance());

        assertTrue(result.isError(), "违约应转 error 结果");
        String msg = String.valueOf(result.value());
        assertTrue(msg.contains("输出违约"), msg);
        assertTrue(msg.contains("typed"), "应点名工具: " + msg);
    }

    @Test
    void contractCompliantResultPasses() {
        JsonNode good = JsonNodeFactory.instance.objectNode().put("value", "你好");
        tools().register(root, typedTool("typed", good, objectSchemaRequiring("value", "string")));

        ToolResult result = tools().execute("typed", NullNode.getInstance());

        assertFalse(result.isError(), "合规结果应放行: " + result.value());
        assertEquals(good, result.value());
    }

    @Test
    void undeclaredOutputStaysPermissive() {
        // 未声明契约：返回什么（哪怕是奇怪形态）都透传
        tools().register(root, typedTool("freeform", "随便什么", null));

        ToolResult result = tools().execute("freeform", NullNode.getInstance());

        assertFalse(result.isError(), "未声明契约应宽松透传: " + result.value());
        assertEquals("随便什么", result.value());
    }

    @Test
    void toolExceptionStillConvergesToError() {
        // 契约不改变工具异常收敛路径：抛错的工具仍收敛为 error（且不再触发契约校验）
        ToolDefinition broken = new ToolDefinition() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public String description() {
                return "抛错工具";
            }

            @Override
            public JsonNode parameters() {
                return NullNode.getInstance();
            }

            @Override
            public JsonNode output() {
                return objectSchemaRequiring("value", "string");
            }

            @Override
            public Object execute(ToolExecution execution) {
                throw new IllegalStateException("本体炸了");
            }
        };
        tools().register(root, broken);

        ToolResult result = tools().execute("broken", NullNode.getInstance());

        assertTrue(result.isError());
        assertTrue(String.valueOf(result.value()).contains("本体炸了"),
                "异常收敛优先于契约校验: " + result.value());
    }

    // === guard ===

    @Test
    void guardReasonDeniesAndNullAllows() {
        ToolDefinition tool = typedTool("guarded", "ok", null);
        tools().register(root, tool);
        tools().guard(root, exec -> "危险".equals(exec.args().path("input").asText(""))
                ? "检测到危险操作" : null);

        ToolResult denied = tools().execute("guarded",
                JsonNodeFactory.instance.objectNode().put("input", "危险"));
        assertTrue(denied.isError(), "guard 返回理由应拒绝");
        String msg = String.valueOf(denied.value());
        assertTrue(msg.contains("检测到危险操作"), msg);
        assertTrue(msg.contains("guard"), "应署名 guard: " + msg);

        ToolResult allowed = tools().execute("guarded",
                JsonNodeFactory.instance.objectNode().put("input", "安全"));
        assertFalse(allowed.isError(), "guard 返回 null 应放行: " + allowed.value());
    }

    @Test
    void guardsRunInOrderAndFirstDenialShortCircuits() {
        ToolDefinition tool = typedTool("guarded", "ok", null);
        tools().register(root, tool);
        AtomicInteger secondRuns = new AtomicInteger();
        tools().guard(root, exec -> "第一个 guard 拒绝");
        tools().guard(root, exec -> {
            secondRuns.incrementAndGet();
            return null;
        });

        ToolResult result = tools().execute("guarded", NullNode.getInstance());

        assertTrue(result.isError());
        assertTrue(String.valueOf(result.value()).contains("第一个 guard 拒绝"),
                "首个拒绝即终局: " + result.value());
        assertEquals(0, secondRuns.get(), "首个拒绝后后续 guard 不再执行");
    }

    @Test
    void guardDenialStopsBodyFromRunning() {
        // "工具本体之前"：guard 拒绝的调用不能抵达本体
        AtomicInteger bodyRuns = new AtomicInteger();
        tools().register(root, typedTool("guarded", "ok", null, bodyRuns));
        tools().guard(root, exec -> "准入拦下");

        tools().execute("guarded", NullNode.getInstance());

        assertEquals(0, bodyRuns.get(), "guard 拒绝后工具本体不得执行");
    }

    @Test
    void guardDenialCannotBeFlippedBack() {
        // pre-execute 监听器与 post-execute 监听器都无法翻回 guard 的拒绝
        ToolDefinition tool = typedTool("guarded", "ok", null);
        tools().register(root, tool);
        tools().guard(root, exec -> "guard 拒绝");

        root.on(ToolsService.PRE_EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> next.invoke(exec));
        root.on(ToolsService.POST_EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    // 尝试把结果改写为正常形态——guard 拒绝直接返回，本监听器根本不执行
                    exec.setResult("被翻回了");
                    return next.invoke(exec);
                });

        ToolResult result = tools().execute("guarded", NullNode.getInstance());

        assertTrue(result.isError(), "guard 拒绝不可翻回");
        assertTrue(String.valueOf(result.value()).contains("guard 拒绝"),
                "结果保持 guard 的拒绝: " + result.value());
    }

    @Test
    void guardRunsAfterApproval() {
        // 时机：审批拒绝 → guard 不执行（guard 在审批之后，被拒绝的调用到不了 guard）
        // 声明需审批的工具（工具自身声明，不经监听器）+ always-deny → 审批拒绝
        ToolDefinition audited = new ToolDefinition() {
            @Override
            public String name() {
                return "audited";
            }

            @Override
            public String description() {
                return "需审批工具";
            }

            @Override
            public JsonNode parameters() {
                return NullNode.getInstance();
            }

            @Override
            public boolean requiresApproval() {
                return true;
            }

            @Override
            public Object execute(ToolExecution execution) {
                return "ok";
            }
        };
        tools().register(root, audited);
        AtomicInteger guardRuns = new AtomicInteger();
        tools().guard(root, exec -> {
            guardRuns.incrementAndGet();
            return null;
        });
        root.plugin(new ApprovalPlugin(),
                JsonNodeFactory.instance.objectNode().put("policy", "always-deny")).awaitStartup();

        ToolResult result = tools().execute("audited", NullNode.getInstance());

        assertTrue(result.isError(), "审批应拒绝: " + result.value());
        assertTrue(String.valueOf(result.value()).contains("被审批策略拒绝"),
                String.valueOf(result.value()));
        assertEquals(0, guardRuns.get(), "审批拒绝后 guard 不应执行（guard 在审批之后）");
    }

    @Test
    void guardDiesWithRegisteringScope() {
        // 生命周期局部：guard 挂在注册插件作用域上，插件停止后 guard 失效
        PluginHandle guardOwner = root.plugin(new Plugin<Void>() {
            @Override
            public Set<String> inject() {
                return Set.of(ToolsService.SERVICE_NAME);
            }

            @Override
            public Class<Void> configType() {
                return null;
            }

            @Override
            public Disposable apply(Context ctx, Void config) {
                ctx.as(ToolsView.class).tools().guard(ctx, exec -> "插件内 guard");
                return null;
            }
        }, null);
        guardOwner.awaitStartup();
        tools().register(root, typedTool("guarded", "ok", null));

        ToolResult before = tools().execute("guarded", NullNode.getInstance());
        assertTrue(before.isError(), "插件活着时 guard 生效");

        guardOwner.dispose();

        ToolResult after = tools().execute("guarded", NullNode.getInstance());
        assertFalse(after.isError(), "插件停止后 guard 摘除: " + after.value());
    }
}
