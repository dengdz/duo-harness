package dev.duo.harness.core.api;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 B（内核编程 API）最小闭环用例：插件加载、config 绑定、effect 逆序回滚、级联。
 * 只断言公共 API 的可观测行为，不触实现细节。
 */
class MinimalPluginLoopTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：MinimalPluginLoopTest —— 插件闭环：apply 运行、config 严格绑定（缺字段/类型错点名）、effect 逆序回滚、销毁后拒绝注册（12 用例） ===");
    }


    /** 示例 config record：验证 Map → 强类型绑定。 */
    record GreetingConfig(String greeting, int times) {}

    /** 记录副作用执行顺序的测试插件。 */
    static class RecordingPlugin implements Plugin<GreetingConfig> {
        final List<String> log;
        final List<GreetingConfig> received = new ArrayList<>();

        RecordingPlugin(List<String> log) {
            this.log = log;
        }

        @Override
        public Class<GreetingConfig> configType() {
            return GreetingConfig.class;
        }

        @Override
        public Disposable apply(Context ctx, GreetingConfig config) {
            received.add(config);
            ctx.effect(() -> log.add("effect-1"));
            ctx.effect(() -> log.add("effect-2"));
            return () -> log.add("apply-disposer");
        }
    }

    @Test
    void applyRunsAndBindsConfigRecord() {
        List<String> log = new ArrayList<>();
        RecordingPlugin plugin = new RecordingPlugin(log);
        Context root = Context.root();

        root.plugin(plugin, Map.of("greeting", "你好", "times", 3));

        assertEquals(1, plugin.received.size());
        assertEquals("你好", plugin.received.get(0).greeting());
        assertEquals(3, plugin.received.get(0).times());
    }

    @Test
    void configBindingFailureReportsPluginAndField() {
        Context root = Context.root();
        // 缺 greeting 字段且 times 类型错误——绑定必须失败且可定位
        Plugin<GreetingConfig> plugin = new RecordingPlugin(new ArrayList<>());

        PluginConfigException e = assertThrows(PluginConfigException.class,
                () -> root.plugin(plugin, Map.of("times", "不是数字")));
        assertTrue(e.getMessage().contains(RecordingPlugin.class.getName()),
                "报错应点名插件: " + e.getMessage());
        assertTrue(e.getMessage().contains("times"),
                "报错应定位到出错字段: " + e.getMessage());
    }

    @Test
    void disposedScopeRejectsEffectAndPlugin() {
        Context root = Context.root();
        root.dispose();

        assertThrows(PluginException.class, () -> root.effect(() -> {}));
        assertThrows(PluginException.class,
                () -> root.plugin(new RecordingPlugin(new ArrayList<>()), null));
    }

    @Test
    void missingRequiredFieldFailsBinding() {
        Context root = Context.root();
        Plugin<GreetingConfig> plugin = new RecordingPlugin(new ArrayList<>());

        PluginConfigException e = assertThrows(PluginConfigException.class,
                () -> root.plugin(plugin, Map.of("times", 1)));
        assertTrue(e.getMessage().contains(RecordingPlugin.class.getName()),
                "报错应点名插件: " + e.getMessage());
    }

    @Test
    void declaredConfigRejectsNullRawConfig() {
        Context root = Context.root();
        Plugin<GreetingConfig> plugin = new RecordingPlugin(new ArrayList<>());

        PluginConfigException e = assertThrows(PluginConfigException.class,
                () -> root.plugin(plugin, null));
        assertTrue(e.getMessage().contains("未提供配置"),
                "声明了 config 类型却不给配置应点名报错: " + e.getMessage());
    }

    @Test
    void nullArgumentsFailFast() {
        Context root = Context.root();

        assertThrows(NullPointerException.class, () -> root.effect(null));
        assertThrows(NullPointerException.class, () -> root.plugin(null, null));
    }

    @Test
    void disposeAggregatesRollbackErrorsWithSuppressed() {
        Context root = Context.root();
        root.effect(() -> {
            throw new IllegalStateException("先注册的错");
        });
        root.effect(() -> {
            throw new IllegalStateException("后注册的错");
        });

        PluginException e = assertThrows(PluginException.class, root::dispose);

        // 逆序执行：后注册的先抛 → 成为主异常 cause；
        // 先注册的原始异常进 suppressed，互不掩盖
        assertEquals("后注册的错", e.getCause().getMessage());
        assertEquals(1, e.getSuppressed().length);
        assertEquals("先注册的错", e.getSuppressed()[0].getMessage());
    }

    @Test
    void applyFailureRollsBackAndPreservesCause() {
        List<String> log = new ArrayList<>();
        Context root = Context.root();
        Plugin<Void> failing = new Plugin<>() {
            @Override
            public Class<Void> configType() {
                return null;
            }

            @Override
            public Disposable apply(Context ctx, Void config) {
                ctx.effect(() -> log.add("已回滚"));
                throw new IllegalStateException("业务炸了");
            }
        };

        // 启动失败不阻断 plugin()：错误统一经 handle（awaitStartup 重抛，六态模型）
        PluginHandle handle = root.plugin(failing, null);
        assertEquals(PluginState.FAILED, handle.state());

        PluginException e = assertThrows(PluginException.class, handle::awaitStartup);
        assertTrue(e.getCause() instanceof IllegalStateException);
        assertEquals("业务炸了", e.getCause().getMessage());
        // 半途注册的副作用被清理，不留残留
        assertEquals(List.of("已回滚"), log);
    }

    @Test
    void effectsRollBackInReverseOrder() {
        List<String> log = new ArrayList<>();
        RecordingPlugin plugin = new RecordingPlugin(log);
        Context root = Context.root();
        root.plugin(plugin, Map.of("greeting", "hi", "times", 1));

        root.dispose();

        // 注册逆序：apply 返回值最后注册、最先回滚，随后 effect-2、effect-1
        assertEquals(List.of("apply-disposer", "effect-2", "effect-1"), log);
    }

    @Test
    void parentDisposeCascadesToChildPlugins() {
        List<String> log = new ArrayList<>();
        Context root = Context.root();

        Plugin<Void> child = new Plugin<>() {
            @Override
            public Class<Void> configType() {
                return null;
            }

            @Override
            public Disposable apply(Context ctx, Void config) {
                // 子插件里再挂孙插件：级联必须传导两层
                ctx.plugin(new Plugin<Void>() {
                    @Override
                    public Class<Void> configType() {
                        return null;
                    }

                    @Override
                    public Disposable apply(Context ctx2, Void config2) {
                        return () -> log.add("孙销毁");
                    }
                }, null);
                return () -> log.add("子销毁");
            }
        };
        root.plugin(child, null);

        root.dispose();

        // child 作用域内注册序：孙实例（apply 中挂载）→ apply 返回的 disposer（最后注册）。
        // 逆序回滚：apply-disposer 先（"子销毁"），随后级联停孙（"孙销毁"）
        assertEquals(List.of("子销毁", "孙销毁"), log);
    }

    @Test
    void effectDisposerIsIdempotent() throws Exception {
        List<String> log = new ArrayList<>();
        Context root = Context.root();
        Disposable remover = root.effect(() -> log.add("once"));

        remover.dispose();
        remover.dispose();

        assertEquals(List.of("once"), log);
        // 幂等移除后，作用域销毁不再重复执行
        root.dispose();
        assertEquals(List.of("once"), log);
    }

    @Test
    void configRejectsRawConfigWhenPluginDeclaresNone() {
        Context root = Context.root();
        Plugin<Void> noConfig = new Plugin<>() {
            @Override
            public Class<Void> configType() {
                return null;
            }

            @Override
            public Disposable apply(Context ctx, Void config) {
                return null;
            }
        };

        assertThrows(PluginConfigException.class,
                () -> root.plugin(noConfig, Map.of("unexpected", 1)));
    }
}
