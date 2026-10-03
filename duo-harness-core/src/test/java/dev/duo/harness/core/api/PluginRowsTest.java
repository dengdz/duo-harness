package dev.duo.harness.core.api;

import dev.duo.harness.core.api.boot.Boot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 S1（boot 全链路）用例：运行期行级控制（ADR-0037 内核受控口一）——
 * 装载即登记、按 id 拔除（依赖方回落 PENDING）、同 id 重装（依赖方自动重载）、
 * 重复/缺席点名、失败两路径（绑定抛错无残留 / apply 失败登记 FAILED 可重试）、
 * pluginRows 服务发布。测试插件用静态嵌套类（FQCN 可寻址）。
 */
class PluginRowsTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：PluginRowsTest —— 运行期行级控制：装载登记、按 id 拔除回落、"
                + "重装自动重载、重复/缺席点名、失败两路径、服务发布（9 用例） ===");
    }

    @TempDir
    Path tempDir;

    // === 测试插件集 ===

    /** 提供问候服务的插件（激活可数）。 */
    public static class GreeterPlugin implements Plugin<Void> {
        static final AtomicInteger ACTIVATIONS = new AtomicInteger();

        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            ACTIVATIONS.incrementAndGet();
            ctx.provide("greeter", "你好");
            return () -> {
            };
        }
    }

    /** 依赖 greeter 的消费插件（apply 次数可数——重载复活的计数证据）。 */
    public static class ConsumerPlugin implements Plugin<Void> {
        static final AtomicInteger ACTIVATIONS = new AtomicInteger();

        @Override
        public Set<String> inject() {
            return Set.of("greeter");
        }

        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            ACTIVATIONS.incrementAndGet();
            return () -> {
            };
        }
    }

    /** 启动即失败的插件（apply 抛错 → FAILED 态路径）。 */
    public static class BrokenPlugin implements Plugin<Void> {
        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            throw new IllegalStateException("装载即炸");
        }
    }

    /** greeter 服务的消费视图。 */
    public interface GreeterView {
        String greeter();
    }

    /** pluginRows 服务的消费视图（方法名 = 服务名，逐字一致）。 */
    public interface PluginRowsView {
        PluginRows pluginRows();
    }

    // === 工具 ===

    private Context bootTree() {
        Path file;
        try {
            file = Files.writeString(tempDir.resolve("plugins-" + System.nanoTime() + ".yml"),
                    """
                            plugins:
                              - id: greeter
                                name: dev.duo.harness.core.api.PluginRowsTest$GreeterPlugin
                              - id: consumer
                                name: dev.duo.harness.core.api.PluginRowsTest$ConsumerPlugin
                            """);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        GreeterPlugin.ACTIVATIONS.set(0);
        ConsumerPlugin.ACTIVATIONS.set(0);
        return Boot.from(file);
    }

    private static List<RowSnapshot> rows(Context root) {
        return PluginRows.of(root).rows();
    }

    private static RowSnapshot rowOf(Context root, String id) {
        return rows(root).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    // === 用例 ===

    @Test
    void bootRowsAreRegisteredInMountOrderWithLiveState() {
        Context root = bootTree();

        List<RowSnapshot> all = rows(root);
        assertEquals(2, all.size());
        assertEquals("greeter", all.get(0).id());
        assertEquals("consumer", all.get(1).id());
        // 插件名口径 = 类 FQCN（与 PluginSnapshot 同规；id 不进 pluginName）
        assertEquals("dev.duo.harness.core.api.PluginRowsTest$GreeterPlugin",
                all.get(0).pluginName());
        assertEquals(PluginState.ACTIVE, rowOf(root, "greeter").state());
        assertEquals(PluginState.ACTIVE, rowOf(root, "consumer").state());
        root.dispose();
    }

    @Test
    void bootPublishesPluginRowsServiceReachableViaView() {
        Context root = bootTree();

        // 根作用域无声明闸门；视图方法名与 SERVICE_NAME 逐字一致（pluginRows）
        PluginRows viaView = root.as(PluginRowsView.class).pluginRows();
        assertEquals(2, viaView.rows().size());
        root.dispose();
    }

    @Test
    void disposeByIdRemovesServiceAndDependentFallsBackToPending() {
        Context root = bootTree();

        PluginRows.of(root).dispose("greeter");

        // 服务消失 + 依赖方即时回落 PENDING（拔除调用线程内同步传导）
        assertFalse(root.hasService("greeter"));
        assertEquals(PluginState.PENDING, rowOf(root, "consumer").state());
        assertFalse(rows(root).stream().anyMatch(r -> r.id().equals("greeter")),
                "拔除后登记摘除");
        assertEquals(1, ConsumerPlugin.ACTIVATIONS.get());
        root.dispose();
    }

    @Test
    void reloadByIdReactivatesDependentAutomatically() {
        Context root = bootTree();
        PluginRows control = PluginRows.of(root);
        control.dispose("greeter");
        assertEquals(PluginState.PENDING, rowOf(root, "consumer").state());

        PluginHandle reloaded = control.load("greeter", new GreeterPlugin(), null);

        // 装载同步激活（apply 于调用线程执行）：服务回归、依赖方自动重载、apply 计数 +1
        assertNotNull(reloaded);
        assertEquals(PluginState.ACTIVE, reloaded.state());
        assertEquals(PluginState.ACTIVE, rowOf(root, "consumer").state());
        assertEquals("你好", root.as(GreeterView.class).greeter());
        assertEquals(2, ConsumerPlugin.ACTIVATIONS.get());
        root.dispose();
    }

    @Test
    void duplicateIdLoadIsNamed() {
        Context root = bootTree();

        PluginException e = assertThrows(PluginException.class,
                () -> PluginRows.of(root).load("greeter", new GreeterPlugin(), null));

        assertTrue(e.getMessage().contains("greeter"), e.getMessage());
        assertTrue(e.getMessage().contains("已装载"), e.getMessage());
        root.dispose();
    }

    @Test
    void bindingFailureLoadThrowsAndLeavesNoResidue() {
        Context root = bootTree();
        PluginRows control = PluginRows.of(root);

        // 绑定失败同步抛出（不接受 config 的插件收到了 rawConfig），登记无残留
        assertThrows(PluginConfigException.class,
                () -> control.load("misconfig", new GreeterPlugin(), Map.of("x", 1)));

        assertFalse(rows(root).stream().anyMatch(r -> r.id().equals("misconfig")));
        root.dispose();
    }

    @Test
    void applyFailureRowIsRegisteredAsFailedAndRetriable() {
        Context root = bootTree();
        PluginRows control = PluginRows.of(root);

        // apply 失败不抛（错误统一经 handle）：行登记为 FAILED，可拔除后同 id 重试
        PluginHandle failed = assertDoesNotThrow(
                () -> control.load("broken", new BrokenPlugin(), null));
        assertEquals(PluginState.FAILED, failed.state());
        assertEquals(PluginState.FAILED, rowOf(root, "broken").state());

        control.dispose("broken");
        // 重试件用无服务发布的 ConsumerPlugin：GreeterPlugin 会撞树上既有 "greeter" 同名互斥
        PluginHandle retry = assertDoesNotThrow(
                () -> control.load("broken", new ConsumerPlugin(), null));
        assertEquals(PluginState.ACTIVE, retry.state());
        root.dispose();
    }

    @Test
    void absentIdDisposeAndGetAreNamed() {
        Context root = bootTree();
        PluginRows control = PluginRows.of(root);

        PluginException disposeError = assertThrows(PluginException.class,
                () -> control.dispose("no-such-row"));
        PluginException getError = assertThrows(PluginException.class,
                () -> control.get("no-such-row"));

        assertTrue(disposeError.getMessage().contains("no-such-row"), disposeError.getMessage());
        assertTrue(disposeError.getMessage().contains("未装载"), disposeError.getMessage());
        assertTrue(getError.getMessage().contains("no-such-row"), getError.getMessage());
        root.dispose();
    }

    @Test
    void programmaticRootGetsControlWithoutBoot() {
        Context root = Context.root();
        GreeterPlugin.ACTIVATIONS.set(0);

        // 编程挂载通道：不走 boot 也有行级控制（of + load/dispose 对称）
        PluginRows control = PluginRows.of(root);
        control.load("solo", new GreeterPlugin(), null);

        assertEquals(1, control.rows().size());
        assertEquals("你好", root.as(GreeterView.class).greeter());
        control.dispose("solo");
        assertFalse(root.hasService("greeter"));
        assertEquals(0, control.rows().size());
        root.dispose();
    }
}
