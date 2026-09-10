package dev.duo.harness.core.api;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 B（内核编程 API）服务域用例：provide/as 视图寻址、inject 许可、
 * 依赖缺失挂起、注销停依赖方、级联传导。只断言公共 API 的可观测行为。
 */
class ServicesTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ServicesTest —— 服务注入：provide/Service 基类发布、as 视图寻址（方法名即服务名+类型校验）、inject 声明许可、依赖缺失挂起、注销级联停依赖方（14 用例） ===");
    }


    /** 测试用服务契约与实现。 */
    interface Tools {
        String run(String input);
    }

    static class EchoTools implements Tools {
        @Override
        public String run(String input) {
            return "echo:" + input;
        }
    }

    /** 视图接口：方法名即服务名。 */
    interface ToolsView {
        Tools tools();
    }

    /** 视图方法返回类型与实例不符的探测视图。 */
    interface WrongTypeView {
        Integer tools();
    }

    @Test
    void provideAndResolveThroughViewInterface() {
        Context root = Context.root();
        root.provide("tools", new EchoTools());

        Tools tools = root.as(ToolsView.class).tools();

        assertEquals("echo:hi", tools.run("hi"));
    }

    /** 测试用 Service 基类子类：构造即发布。 */
    static class GreeterService extends Service {
        GreeterService(Context ctx) {
            super(ctx, "greeter");
        }
    }

    interface GreeterView {
        GreeterService greeter();
    }

    @Test
    void serviceBaseClassPublishesOnConstruction() {
        Context root = Context.root();

        GreeterService service = new GreeterService(root);

        assertSame(service, root.as(GreeterView.class).greeter());
    }

    @Test
    void duplicateProvideFailsLoudly() {
        Context root = Context.root();
        root.provide("tools", new EchoTools());

        PluginException e = assertThrows(PluginException.class,
                () -> root.provide("tools", new EchoTools()));
        assertTrue(e.getMessage().contains("tools"));
        assertTrue(e.getMessage().contains("已"));
    }

    @Test
    void resolveUnprovidedServiceFails() {
        Context root = Context.root();

        PluginException e = assertThrows(PluginException.class,
                () -> root.as(ToolsView.class).tools());
        assertTrue(e.getMessage().contains("未提供"));
    }

    @Test
    void typeMismatchFailsWithBothTypes() {
        Context root = Context.root();
        root.provide("tools", new EchoTools());

        PluginException e = assertThrows(PluginException.class,
                () -> root.as(WrongTypeView.class).tools());
        assertTrue(e.getMessage().contains("EchoTools"));
        assertTrue(e.getMessage().contains("Integer"));
    }

    @Test
    void pluginScopeRequiresInjectDeclarationToRead() {
        Context root = Context.root();
        root.provide("tools", new EchoTools());

        // 根作用域读取不要求声明；插件作用域要求——通过插件 apply 内的读取验证
        List<String> denied = new ArrayList<>();
        root.plugin(new Plugin<Void>() {
            @Override
            public Class<Void> configType() {
                return null;
            }

            @Override
            public Disposable apply(Context ctx, Void config) {
                try {
                    ctx.as(ToolsView.class).tools();
                } catch (PluginException e) {
                    denied.add(e.getMessage());
                }
                return null;
            }
        }, null);

        assertEquals(1, denied.size());
        assertTrue(denied.get(0).contains("未在 inject 中声明"), denied.get(0));
    }

    @Test
    void declaredInjectGrantsReadAccess() {
        Context root = Context.root();
        root.provide("tools", new EchoTools());
        List<String> results = new ArrayList<>();

        root.plugin(new Plugin<Void>() {
            @Override
            public Set<String> inject() {
                return Set.of("tools");
            }

            @Override
            public Class<Void> configType() {
                return null;
            }

            @Override
            public Disposable apply(Context ctx, Void config) {
                results.add(ctx.as(ToolsView.class).tools().run("ok"));
                return null;
            }
        }, null);

        assertEquals(List.of("echo:ok"), results);
    }

    @Test
    void pluginWaitsForMissingServiceThenAutoStarts() throws Exception {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        AtomicInteger started = new AtomicInteger();

        PluginHandle handle = root.plugin(new Plugin<Void>() {
            @Override
            public Set<String> inject() {
                return Set.of("tools");
            }

            @Override
            public Class<Void> configType() {
                return null;
            }

            @Override
            public Disposable apply(Context ctx, Void config) {
                started.incrementAndGet();
                return () -> log.add("停止");
            }
        }, null);

        // 延迟提供服务：等待期间插件应保持 PENDING（未跑 apply）
        Thread.sleep(50);
        assertEquals(0, started.get());
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(50);
                root.provide("tools", new EchoTools());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        handle.awaitStartup();

        assertEquals(1, started.get());
    }

    @Test
    void withdrawingServiceStopsDependentPlugin() throws Exception {
        Context root = Context.root();
        List<String> log = new ArrayList<>();

        Disposable toolsRemover = root.provide("tools", new EchoTools());
        root.plugin(new Plugin<Void>() {
            @Override
            public Set<String> inject() {
                return Set.of("tools");
            }

            @Override
            public Class<Void> configType() {
                return null;
            }

            @Override
            public Disposable apply(Context ctx, Void config) {
                ctx.effect(() -> log.add("依赖方-effect-回滚"));
                return () -> log.add("依赖方-整体-回滚");
            }
        }, null).awaitStartup();

        toolsRemover.dispose();

        // 拔服务 → 依赖方自动停止（其全部副作用回滚）
        assertTrue(log.contains("依赖方-整体-回滚"));
        assertTrue(log.contains("依赖方-effect-回滚"));
    }

    @Test
    void withdrawalStopsOnlyDependents() throws Exception {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        Disposable toolsRemover = root.provide("tools", new EchoTools());
        root.provide("unrelated", new Object());

        PluginHandle dependent = root.plugin(dependentPlugin("tools", log, "依赖方"), null);
        dependent.awaitStartup();
        PluginHandle bystander = root.plugin(dependentPlugin("unrelated", log, "旁观者"), null);
        bystander.awaitStartup();

        toolsRemover.dispose();

        // 只停依赖方；旁观者不受影响
        assertTrue(log.contains("依赖方-停止"));
        assertFalse(log.contains("旁观者-停止"));
    }

    /** 构造依赖单个服务的记录型插件。 */
    private Plugin<Void> dependentPlugin(String serviceName, List<String> log, String tag) {
        return new Plugin<>() {
            @Override
            public Set<String> inject() {
                return Set.of(serviceName);
            }

            @Override
            public Class<Void> configType() {
                return null;
            }

            @Override
            public Disposable apply(Context ctx, Void config) {
                return () -> log.add(tag + "-停止");
            }
        };
    }

    @Test
    void providerPluginStopUnregistersServiceAndCascadesToDependent() throws Exception {
        Context root = Context.root();
        List<String> log = new ArrayList<>();

        // 插件 A：提供 tools 服务
        PluginHandle provider = root.plugin(new Plugin<Void>() {
            @Override
            public Class<Void> configType() {
                return null;
            }

            @Override
            public Disposable apply(Context ctx, Void config) {
                ctx.provide("tools", new EchoTools());
                return () -> log.add("A-停止");
            }
        }, null);
        provider.awaitStartup();

        // 插件 B：依赖 tools
        PluginHandle dependent = root.plugin(dependentPlugin("tools", log, "B"), null);
        dependent.awaitStartup();

        // 停 A → tools 注销 → B 级联停止
        provider.dispose();

        assertTrue(log.contains("A-停止"));
        assertTrue(log.contains("B-停止"));
    }

    @Test
    void asRejectsNonInterfaceType() {
        Context root = Context.root();

        assertThrows(PluginException.class, () -> root.as(EchoTools.class));
    }

    @Test
    void provideDisposerRemovesService() throws Exception {
        Context root = Context.root();
        Disposable remover = root.provide("tools", new EchoTools());
        remover.dispose();

        assertThrows(PluginException.class, () -> root.as(ToolsView.class).tools());
    }

    @Test
    void awaitStartupRethrowsAsyncActivationFailure() {
        Context root = Context.root();

        // 依赖缺失先挂起；坏服务随后提供 → 异步唤醒路径激活失败 → awaitStartup 重抛
        PluginHandle handle = root.plugin(new Plugin<Void>() {
            @Override
            public Set<String> inject() {
                return Set.of("bad");
            }

            @Override
            public Class<Void> configType() {
                return null;
            }

            @Override
            public Disposable apply(Context ctx, Void config) {
                // bad 已声明可读，但字符串实例与视图声明的 Integer 不兼容 → 抛错
                ctx.as(BadView.class).bad();
                return null;
            }
        }, null);
        root.provide("bad", "字符串实例不是 Integer");

        PluginException e = assertThrows(PluginException.class, handle::awaitStartup);
        assertNotNull(e.getCause());
        assertTrue(e.getCause().getMessage().contains("不兼容"), e.getCause().getMessage());
    }

    /** 以 bad 为服务名的类型探测视图。 */
    interface BadView {
        Integer bad();
    }
}
