package dev.duo.harness.core.api;

import dev.duo.harness.core.api.events.PluginStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 B（内核编程 API）依赖驱动生命周期用例：六态迁移、epoch 指纹驱动的
 * 停止/回归重启/换实现重启、失败隔离、状态事件广播。
 */
class LifecycleTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：LifecycleTest —— 六态与 epoch：状态迁移事件广播、依赖消失回 PENDING、服务回归/换实现自动重启、失败隔离（FAILED 兄弟无感）、DISPOSED 终态不复活（10 用例） ===");
    }


    interface Tools {
        String name();
    }

    static class ToolsV1 implements Tools {
        @Override
        public String name() {
            return "v1";
        }
    }

    static class ToolsV2 implements Tools {
        @Override
        public String name() {
            return "v2";
        }
    }

    interface ToolsView {
        Tools tools();
    }

    /** 记录 apply 次数与每次读到的服务实例的依赖方插件。 */
    static class DependentPlugin implements Plugin<Void> {
        final AtomicInteger activations = new AtomicInteger();
        final List<String> seenImplementations = new ArrayList<>();
        final List<String> log;

        DependentPlugin(List<String> log) {
            this.log = log;
        }

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
            activations.incrementAndGet();
            seenImplementations.add(ctx.as(ToolsView.class).tools().name());
            return () -> log.add("卸载");
        }
    }

    private static List<PluginState> statesOf(List<PluginStatus> events, String plugin) {
        return events.stream()
                .filter(s -> s.plugin().contains(plugin))
                .map(PluginStatus::to)
                .toList();
    }

    @Test
    void activationEmitsStatusEvents() {
        Context root = Context.root();
        List<PluginStatus> events = new ArrayList<>();
        root.on(PluginStatus.EVENT, args -> {
            events.add((PluginStatus) args);
            return null;
        });
        root.provide("tools", new ToolsV1());

        PluginHandle handle = root.plugin(new DependentPlugin(new ArrayList<>()), null);

        assertEquals(PluginState.ACTIVE, handle.state());
        assertEquals(List.of(PluginState.LOADING, PluginState.ACTIVE),
                statesOf(events, "DependentPlugin"));
    }

    @Test
    void withdrawalUnloadsToPendingWithRollback() throws Exception {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        Disposable tools = root.provide("tools", new ToolsV1());
        PluginHandle handle = root.plugin(new DependentPlugin(log), null);
        handle.awaitStartup();
        assertEquals(PluginState.ACTIVE, handle.state());

        tools.dispose();

        // 依赖消失 → 卸载回 PENDING（非终态，等待回归），副作用已回滚
        assertEquals(PluginState.PENDING, handle.state());
        assertEquals(List.of("卸载"), log);
    }

    @Test
    void serviceReturnRestartsPluginAutomatically() throws Exception {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        Disposable tools = root.provide("tools", new ToolsV1());
        DependentPlugin plugin = new DependentPlugin(log);
        PluginHandle handle = root.plugin(plugin, null);
        handle.awaitStartup();

        tools.dispose();
        assertEquals(PluginState.PENDING, handle.state());

        root.provide("tools", new ToolsV1());

        // 回归 → 自动重启：apply 第二次执行
        assertEquals(PluginState.ACTIVE, handle.state());
        assertEquals(2, plugin.activations.get());
    }

    @Test
    void implementationSwapRestartsPluginWithNewInstance() throws Exception {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        Disposable v1 = root.provide("tools", new ToolsV1());
        DependentPlugin plugin = new DependentPlugin(log);
        PluginHandle handle = root.plugin(plugin, null);
        handle.awaitStartup();
        assertEquals(List.of("v1"), plugin.seenImplementations);

        v1.dispose();
        root.provide("tools", new ToolsV2());

        // 换实现 → 重启且读到新实例
        assertEquals(PluginState.ACTIVE, handle.state());
        assertEquals(2, plugin.activations.get());
        assertEquals("v2", plugin.seenImplementations.get(1));
    }

    @Test
    void failedStateIsTerminalAndIsolatedFromSiblings() {
        Context root = Context.root();
        root.provide("tools", new ToolsV1());
        List<String> siblingLog = new ArrayList<>();

        PluginHandle failing = root.plugin(new Plugin<Void>() {
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
                throw new IllegalStateException("启动即炸");
            }
        }, null);

        PluginHandle sibling = root.plugin(new DependentPlugin(siblingLog), null);
        sibling.awaitStartup();

        assertEquals(PluginState.FAILED, failing.state());
        PluginException e = assertThrows(PluginException.class, failing::awaitStartup);
        assertEquals("启动即炸", e.getCause().getMessage());
        // 兄弟插件无感
        assertEquals(PluginState.ACTIVE, sibling.state());
    }

    @Test
    void disposedIsTerminalAndDoesNotRestart() throws Exception {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        Disposable tools = root.provide("tools", new ToolsV1());
        DependentPlugin plugin = new DependentPlugin(log);
        PluginHandle handle = root.plugin(plugin, null);
        handle.awaitStartup();

        handle.dispose();
        tools.dispose();
        root.provide("tools", new ToolsV1());

        // DISPOSED 终态：依赖回归也不复活
        assertEquals(PluginState.DISPOSED, handle.state());
        assertEquals(1, plugin.activations.get());
    }

    @Test
    void pendingWaitsUntilAllDependenciesPresent() throws Exception {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        AtomicInteger activations = new AtomicInteger();
        Plugin<Void> dual = new Plugin<Void>() {
            @Override
            public Set<String> inject() {
                return Set.of("tools", "llm");
            }

            @Override
            public Class<Void> configType() {
                return null;
            }

            @Override
            public Disposable apply(Context ctx, Void config) {
                activations.incrementAndGet();
                return null;
            }
        };

        PluginHandle handle = root.plugin(dual, null);
        Disposable tools = root.provide("tools", new ToolsV1());
        assertEquals(PluginState.PENDING, handle.state());
        assertEquals(0, activations.get());

        Disposable llm = root.provide("llm", new Object());
        assertEquals(PluginState.ACTIVE, handle.state());
        assertEquals(1, activations.get());

        // 换其中一个依赖 → 重启
        llm.dispose();
        assertEquals(PluginState.PENDING, handle.state());
        root.provide("llm", new Object());
        assertEquals(PluginState.ACTIVE, handle.state());
        assertEquals(2, activations.get());
    }

    @Test
    void unrelatedServiceChangeDoesNotRestartDependents() {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        root.provide("tools", new ToolsV1());
        DependentPlugin plugin = new DependentPlugin(log);
        PluginHandle handle = root.plugin(plugin, null);
        handle.awaitStartup();

        root.provide("unrelated", new Object());

        // epoch 指纹不含 unrelated：不触发重启
        assertEquals(1, plugin.activations.get());
        assertEquals(PluginState.ACTIVE, handle.state());
    }

    @Test
    void fullUnloadEmitsUnloadAndPendingEvents() throws Exception {
        Context root = Context.root();
        List<PluginStatus> events = new ArrayList<>();
        root.on(PluginStatus.EVENT, args -> {
            events.add((PluginStatus) args);
            return null;
        });
        Disposable tools = root.provide("tools", new ToolsV1());
        PluginHandle handle = root.plugin(new DependentPlugin(new ArrayList<>()), null);
        handle.awaitStartup();
        events.clear();

        tools.dispose();

        assertEquals(List.of(PluginState.UNLOADING, PluginState.PENDING),
                statesOf(events, "DependentPlugin"));
        assertFalse(statesOf(events, "DependentPlugin").contains(PluginState.DISPOSED));
    }

    @Test
    void disposeEmitsTerminalEvents() {
        Context root = Context.root();
        List<PluginStatus> events = new ArrayList<>();
        root.on(PluginStatus.EVENT, args -> {
            events.add((PluginStatus) args);
            return null;
        });
        root.provide("tools", new ToolsV1());
        PluginHandle handle = root.plugin(new DependentPlugin(new ArrayList<>()), null);
        handle.awaitStartup();
        events.clear();

        handle.dispose();

        List<PluginState> states = statesOf(events, "DependentPlugin");
        assertTrue(states.contains(PluginState.UNLOADING));
        assertTrue(states.contains(PluginState.DISPOSED));
        assertEquals(PluginState.DISPOSED, states.get(states.size() - 1));
    }
}
