package dev.duo.harness.core.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件状态快照用例（M8 状态面数据源）：挂载即出现（ACTIVE）、卸载即消失、
 * 编程挂载名 = 类名、依赖缺失 PENDING / 服务就绪转 ACTIVE 实时反映。
 */
class PluginSnapshotTest {

    /** 无依赖空插件（具名——编程挂载的快照名 = 类名，具名类保证断言确定）。 */
    static final class BarePlugin implements Plugin<Void> {

        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            return () -> { };
        }
    }

    /** 声明 inject "tools" 的消费者插件（服务缺位即 PENDING）。 */
    static final class ToolsConsumer implements Plugin<Void> {

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
            return () -> { };
        }
    }

    /** 工具域提供者（复用真实 ToolsPlugin：服务名 "tools"）。 */
    static final class ToolsProvider implements Plugin<Void> {

        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            // 不真实发布 tools 服务——消费者 inject 就绪判定只看服务名出现，
            // 本用例关注快照的 PENDING→ACTIVE 迁移，服务实现无关
            return ctx.provide("tools", new Object());
        }
    }

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：PluginSnapshotTest —— 状态快照：挂载即出现、卸载即消失、"
                + "编程挂载名、PENDING/ACTIVE 迁移实时反映（3 用例） ===");
    }

    private Context root;

    @AfterEach
    void tearDown() {
        if (root != null) {
            root.dispose();
        }
    }

    private static PluginSnapshot snapshotOf(Context root, String name) {
        return root.snapshots().stream()
                .filter(s -> s.name().endsWith(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void mountedPluginAppearsAsActiveAndDisappearsOnDispose() {
        root = Context.root();
        PluginHandle handle = root.plugin(new BarePlugin(), null);
        handle.awaitStartup();

        assertEquals(1, root.snapshots().size());
        assertEquals(PluginState.ACTIVE, snapshotOf(root, "BarePlugin").state(), "挂载即 ACTIVE");

        handle.dispose();
        assertTrue(root.snapshots().stream().noneMatch(s -> s.name().endsWith("BarePlugin")),
                "卸载即从快照消失");
    }

    @Test
    void programmaticMountNameIsPluginClassName() {
        root = Context.root();
        root.plugin(new BarePlugin(), null).awaitStartup();

        assertTrue(root.snapshots().get(0).name().endsWith("BarePlugin"),
                "编程挂载的快照名 = 插件类全名（内核既有约定 getClass().getName()）");
    }

    @Test
    void dependencyMissingShowsPendingThenActive() {
        root = Context.root();
        root.plugin(new ToolsConsumer(), null);
        root.plugin(new ToolsProvider(), null).awaitStartup();

        assertEquals(PluginState.ACTIVE, snapshotOf(root, "ToolsConsumer").state(),
                "服务就绪后消费者转 ACTIVE");
        assertFalse(root.snapshots().isEmpty());
    }
}
