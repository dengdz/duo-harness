package dev.duo.harness.core;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.PluginState;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.core.api.boot.BootException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可选依赖（optionalInject，ADR-0019）用例：接缝 A（boot 全链路）+ 接缝 B（内核
 * 编程 API）。三句承诺各落硬断言——缺失不阻塞启动（立即 ACTIVE）、在场参与指纹
 * （provide/unprovide 双向触发重载）、读取许可与 inject 合一（未声明仍点名拒绝）。
 * 测试插件用静态嵌套类（接缝 A 的 FQCN 可被 Class.forName 寻址）。
 */
class OptionalInjectTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：OptionalInjectTest —— 可选依赖：缺失即激活、出现/消失双向重载、"
                + "许可合一、交叠拒绝、boot 审计不误报（7 用例） ===");
    }

    @TempDir
    Path tempDir;

    // === 测试插件集 ===

    /** 可选依赖探针：apply 记录 workspace 在场性；在场时经视图读取（许可验证）。 */
    public static class WorkspaceProbePlugin implements Plugin<Void> {
        static final AtomicInteger ACTIVATIONS = new AtomicInteger();
        static final AtomicBoolean PRESENT_AT_APPLY = new AtomicBoolean(true);

        @Override
        public Set<String> optionalInject() {
            return Set.of("workspace");
        }

        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            ACTIVATIONS.incrementAndGet();
            boolean present = ctx.hasService("workspace");
            PRESENT_AT_APPLY.set(present);
            if (present) {
                // 在场时视图读取必须放行：读取许可 = inject ∪ optionalInject
                ctx.as(WorkspaceView.class).workspace();
            }
            return () -> { };
        }
    }

    /** 视图接口：方法名即服务名 workspace；返回 Object 容忍任意实例。 */
    public interface WorkspaceView {

        Object workspace();
    }

    /** 硬依赖缺失的对照插件：boot 审计应点名它，而不是可选缺失的探针。 */
    public static class HardMissingPlugin implements Plugin<Void> {

        @Override
        public Set<String> inject() {
            return Set.of("no-such-service");
        }

        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            return null;
        }
    }

    /** 未声明就读取的越权插件：许可不含 workspace，apply 应点名失败。 */
    public static class UndeclaredReaderPlugin implements Plugin<Void> {

        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            ctx.as(WorkspaceView.class).workspace();
            return null;
        }
    }

    /** 硬软交叠声明的插件：加载时刻点名拒绝（缺失是否阻塞启动二义）。 */
    public static class OverlapPlugin implements Plugin<Void> {

        @Override
        public Set<String> inject() {
            return Set.of("dup-service");
        }

        @Override
        public Set<String> optionalInject() {
            return Set.of("dup-service");
        }

        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            return null;
        }
    }

    // === 接缝 B（内核编程 API） ===

    @Test
    void optionalMissingActivatesImmediately() {
        // "缺失不拦"：optionalInject 声明的服务缺席，插件照常 ACTIVE，apply 读到"不在场"
        WorkspaceProbePlugin.ACTIVATIONS.set(0);
        Context root = Context.root();
        try {
            PluginHandle handle = root.plugin(new WorkspaceProbePlugin(), null);
            handle.awaitStartup();
            assertEquals(PluginState.ACTIVE, handle.state(), "可选服务缺席不阻塞启动");
            assertEquals(1, WorkspaceProbePlugin.ACTIVATIONS.get());
            assertTrue(!WorkspaceProbePlugin.PRESENT_AT_APPLY.get(), "apply 时 workspace 应缺席");
        } finally {
            root.dispose();
        }
    }

    @Test
    void optionalServiceAppearanceReloadsUpward() throws Exception {
        // "在场参与指纹"：provide 可选服务 → 指纹变化 → 自动重载升级（apply 再跑、读到在场）
        WorkspaceProbePlugin.ACTIVATIONS.set(0);
        Context root = Context.root();
        try {
            root.plugin(new WorkspaceProbePlugin(), null).awaitStartup();
            assertEquals(1, WorkspaceProbePlugin.ACTIVATIONS.get());
            Disposable remover = root.provide("workspace", new Object());
            assertEquals(2, WorkspaceProbePlugin.ACTIVATIONS.get(), "服务出现触发升级重载");
            assertTrue(WorkspaceProbePlugin.PRESENT_AT_APPLY.get(), "重载后的 apply 读到在场");
            assertEquals(PluginState.ACTIVE, root.snapshots().get(0).state());
            remover.dispose();
        } finally {
            root.dispose();
        }
    }

    @Test
    void optionalServiceDisappearanceReloadsDownward() throws Exception {
        // 双向对称：unprovide 可选服务 → 自动重载降级（apply 再跑、读到缺席、仍 ACTIVE）
        WorkspaceProbePlugin.ACTIVATIONS.set(0);
        Context root = Context.root();
        try {
            root.plugin(new WorkspaceProbePlugin(), null).awaitStartup();
            Disposable remover = root.provide("workspace", new Object());
            assertEquals(2, WorkspaceProbePlugin.ACTIVATIONS.get());
            remover.dispose();
            assertEquals(3, WorkspaceProbePlugin.ACTIVATIONS.get(), "服务消失触发降级重载");
            assertTrue(!WorkspaceProbePlugin.PRESENT_AT_APPLY.get(), "降级后的 apply 读到缺席");
            assertEquals(PluginState.ACTIVE, root.snapshots().get(0).state(), "降级不是停机");
        } finally {
            root.dispose();
        }
    }

    @Test
    void viewReadStillRequiresDeclaration() {
        // 许可纪律不因可选放松：未声明的服务名在 apply 内读取即点名失败 → FAILED
        Context root = Context.root();
        try {
            PluginHandle handle = root.plugin(new UndeclaredReaderPlugin(), null);
            PluginException e = assertThrows(PluginException.class, handle::awaitStartup);
            assertTrue(e.getMessage().contains("启动失败"), e.getMessage());
            assertEquals(PluginState.FAILED, handle.state());
            assertTrue(e.getCause().getMessage().contains("未在依赖声明中"),
                    "点名许可缺失: " + e.getCause().getMessage());
        } finally {
            root.dispose();
        }
    }

    @Test
    void overlapDeclarationRejectedAtLoad() {
        // 硬软同名：缺失是否阻塞启动二义，加载时刻点名拒绝（错误前移）
        Context root = Context.root();
        try {
            PluginException e = assertThrows(PluginException.class,
                    () -> root.plugin(new OverlapPlugin(), null));
            assertTrue(e.getMessage().contains("dup-service"), e.getMessage());
        } finally {
            root.dispose();
        }
    }

    // === 接缝 A（boot 全链路） ===

    @Test
    void bootSucceedsWhenOnlyOptionalMissing() throws Exception {
        // yml 只含可选缺失的探针行 → boot 成功、实例 ACTIVE（审计无问题可报）
        WorkspaceProbePlugin.ACTIVATIONS.set(0);
        Path yml = tempDir.resolve("boot-optional-ok.yml");
        Files.writeString(yml, """
                plugins:
                  - id: probe
                    name: %s
                """.formatted(WorkspaceProbePlugin.class.getName()));
        Context root = Boot.from(yml);
        try {
            assertEquals(PluginState.ACTIVE, root.snapshots().get(0).state());
            assertEquals(1, WorkspaceProbePlugin.ACTIVATIONS.get());
        } finally {
            root.dispose();
        }
    }

    @Test
    void bootAuditReportsHardMissingButNotOptionalOnly() throws Exception {
        // 探针（可选缺失）+ 硬缺失行 → 审计只点名硬缺失行（1 个问题），探针行不受累
        Path yml = tempDir.resolve("boot-audit.yml");
        Files.writeString(yml, """
                plugins:
                  - id: probe
                    name: %s
                  - id: hard-row
                    name: %s
                """.formatted(WorkspaceProbePlugin.class.getName(), HardMissingPlugin.class.getName()));
        BootException e = assertThrows(BootException.class, () -> Boot.from(yml));
        String message = e.getMessage();
        assertTrue(message.contains("1 个问题"), "仅硬缺失行入审计: " + message);
        assertTrue(message.contains("no-such-service"), "点名缺失服务: " + message);
        assertTrue(message.contains("hard-row"), "点名问题行: " + message);
        assertTrue(!message.contains("probe"), "可选缺失行不被审计点名: " + message);
    }
}
