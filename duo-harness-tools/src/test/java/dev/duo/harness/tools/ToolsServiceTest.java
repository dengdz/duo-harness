package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.events.WaterfallListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具域骨架与三段管线用例：经内核插件树挂载 ToolsPlugin 后，
 * 走服务视图注册/执行，验证三段 waterfall 语义与生命周期绑定。
 */
class ToolsServiceTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ToolsServiceTest —— 工具域三段管线：pre 准入否决（否决后本体不执行）、execute around 包装、post 结果改写与转错误、工具异常收敛 error 结果、插件停止自动注销工具（10 用例） ===");
    }


    /** 服务视图接口（方法名即服务名）。 */
    interface ToolsView {
        ToolsService tools();
    }

    /** 最小工具：回声。 */
    static class EchoTool implements ToolDefinition {
        final AtomicInteger executions = new AtomicInteger();

        @Override
        public String name() {
            return "echo";
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
        public Object execute(ToolExecution execution) {
            executions.incrementAndGet();
            return "echo:" + execution.args().path("input").asText("");
        }
    }

    private Context root;

    @BeforeEach
    void mountToolsDomain() {
        root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
    }

    @AfterEach
    void unmount() throws Exception {
        root.dispose();
    }

    private ToolsService tools() {
        return root.as(ToolsView.class).tools();
    }

    @Test
    void registerAndExecuteThroughPipeline() {
        EchoTool echo = new EchoTool();
        tools().register(root, echo);

        ToolResult result = tools().execute("echo",
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .createObjectNode().put("input", "你好"));

        assertFalse(result.isError());
        assertEquals("echo:你好", result.value());
        assertEquals(1, echo.executions.get());
    }

    @Test
    void unknownToolIsNamed() {
        ToolNotFoundException e = assertThrows(ToolNotFoundException.class,
                () -> tools().execute("ghost", NullNode.getInstance()));
        assertTrue(e.getMessage().contains("ghost"));
    }

    @Test
    void preExecuteCanVetoWithoutRunningTool() {
        EchoTool echo = new EchoTool();
        tools().register(root, echo);
        root.on(ToolsService.PRE_EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    if ("echo".equals(exec.toolName())) {
                        exec.deny("测试否决");
                        return false;
                    }
                    return next.invoke(exec);
                });

        ToolResult result = tools().execute("echo", NullNode.getInstance());

        assertTrue(result.isError());
        assertTrue(String.valueOf(result.value()).contains("测试否决"));
        assertEquals(0, echo.executions.get(), "否决后工具本体不得执行");
    }

    @Test
    void executeSegmentCanWrapTheToolBody() {
        EchoTool echo = new EchoTool();
        tools().register(root, echo);
        root.on(ToolsService.EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    Boolean inner = next.invoke(exec);
                    exec.setResult("包装(" + exec.result() + ")");
                    return inner;
                });

        ToolResult result = tools().execute("echo", NullNode.getInstance());

        assertEquals("包装(echo:)", result.value());
    }

    @Test
    void postExecuteCanRewriteResult() {
        tools().register(root, new EchoTool());
        root.on(ToolsService.POST_EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    exec.setResult("治理后:" + exec.result());
                    return next.invoke(exec);
                });

        ToolResult result = tools().execute("echo", NullNode.getInstance());

        assertEquals("治理后:echo:", result.value());
        assertFalse(result.isError());
    }

    @Test
    void postExecuteCanTurnResultIntoError() {
        tools().register(root, new EchoTool());
        root.on(ToolsService.POST_EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    exec.markError("结果被治理拦截");
                    return false;
                });

        ToolResult result = tools().execute("echo", NullNode.getInstance());

        assertTrue(result.isError());
        assertEquals("结果被治理拦截", result.value());
    }

    @Test
    void toolExceptionBecomesErrorResultNotThrown() {
        tools().register(root, new ToolDefinition() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public String description() {
                return "必炸工具";
            }

            @Override
            public JsonNode parameters() {
                return NullNode.getInstance();
            }

            @Override
            public Object execute(ToolExecution execution) {
                throw new IllegalStateException("工具内部炸了");
            }
        });

        ToolResult result = assertDoesNotThrow(() -> tools().execute("boom", NullNode.getInstance()));

        assertTrue(result.isError());
        assertTrue(String.valueOf(result.value()).contains("工具内部炸了"),
                String.valueOf(result.value()));
    }

    @Test
    void duplicateRegistrationIsNamed() {
        tools().register(root, new EchoTool());

        PluginException e = assertThrows(PluginException.class,
                () -> tools().register(root, new EchoTool()));
        assertTrue(e.getMessage().contains("echo"));
    }

    @Test
    void pluginStopUnregistersItsTools() throws Exception {
        // 工具注册进插件作用域：插件停止 → 工具自动注销
        PluginHandle toolPlugin = root.plugin(new Plugin<Void>() {
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
                ToolsService tools = ctx.as(ToolsView.class).tools();
                return tools.register(ctx, new EchoTool());
            }
        }, null);
        toolPlugin.awaitStartup();
        assertDoesNotThrow(() -> tools().execute("echo", NullNode.getInstance()));

        toolPlugin.dispose();

        assertThrows(ToolNotFoundException.class, () -> tools().execute("echo", NullNode.getInstance()));
    }

    @Test
    void dependentPluginSeesToolsServiceViaView() throws Exception {
        // 依赖方插件声明 inject 并经视图拿服务——工具域与插件树的标准协作形态
        AtomicInteger seen = new AtomicInteger();
        PluginHandle dependent = root.plugin(new Plugin<Void>() {
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
                seen.incrementAndGet();
                return null;
            }
        }, null);
        dependent.awaitStartup();

        assertEquals(1, seen.get());
    }
}
