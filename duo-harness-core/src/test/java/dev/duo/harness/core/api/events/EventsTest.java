package dev.duo.harness.core.api.events;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 B（内核编程 API）事件域用例：五种分派模式语义、监听器与作用域
 * 生命周期的绑定、监听器异常隔离。只断言公共 API 的可观测行为。
 */
class EventsTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：EventsTest —— 事件五模式：emit 异常隔离、waterfall 否决/参数改写/返回包装、"
                + "serial·bail 顺序投票、parallel 并发聚合、监听器随作用域摘除（19 用例） ===");
    }


    /** 记录调用轨迹的监听器工厂：args 原样返回（弃权）或返回指定投票值。 */
    private static EventListener recorder(List<String> log, String tag, Object vote) {
        return args -> {
            log.add(tag);
            return vote;
        };
    }

    @Test
    void emitDeliversToAllInRegistrationOrder() {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        root.on("tick", recorder(log, "a", null));
        root.on("tick", recorder(log, "b", null));

        root.emit("tick", "payload");

        assertEquals(List.of("a", "b"), log);
    }

    @Test
    void emitIsolatesListenerErrorsFromSiblingsAndCaller() {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        root.on("tick", args -> {
            throw new IllegalStateException("第一个监听器炸了");
        });
        root.on("tick", recorder(log, "survivor", null));

        assertDoesNotThrow(() -> root.emit("tick", null));
        assertEquals(List.of("survivor"), log);
    }

    @Test
    void parallelRunsAllAndAggregatesErrors() {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        root.on("fan", recorder(log, "ok", null));
        root.on("fan", args -> {
            throw new IllegalStateException("错一");
        });
        root.on("fan", args -> {
            throw new IllegalStateException("错二");
        });

        PluginException e = assertThrows(PluginException.class, () -> root.parallel("fan", null));
        // 并发加入顺序不保证：主异常与 suppressed 合并断言，不依赖次序
        Set<String> messages = new HashSet<>();
        messages.add(e.getCause().getMessage());
        for (Throwable suppressed : e.getSuppressed()) {
            messages.add(suppressed.getMessage());
        }
        assertEquals(Set.of("错一", "错二"), messages);
        // 无失败监听器照常执行完
        assertTrue(log.contains("ok"));
    }

    @Test
    void parallelWithoutListenersIsNoop() {
        Context root = Context.root();
        assertDoesNotThrow(() -> root.parallel("nobody-listens", null));
    }

    @Test
    void serialStopsAtFirstNonNullVote() {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        root.on("vote", recorder(log, "弃权一", null));
        root.on("vote", recorder(log, "弃权二", null));
        root.on("vote", recorder(log, "投票者", "WIN"));
        root.on("vote", recorder(log, "不应执行", "LATE"));

        Object result = root.serial("vote", null);

        assertEquals("WIN", result);
        assertEquals(List.of("弃权一", "弃权二", "投票者"), log);
    }

    @Test
    void serialTreatsFalseAsValidVote() {
        Context root = Context.root();
        root.on("vote", args -> Boolean.FALSE);

        assertEquals(Boolean.FALSE, root.serial("vote", null));
    }

    @Test
    void serialWrapsListenerErrorForCaller() {
        Context root = Context.root();
        root.on("vote", args -> {
            throw new IllegalStateException("投票中断");
        });

        PluginException e = assertThrows(PluginException.class, () -> root.serial("vote", null));
        assertEquals("投票中断", e.getCause().getMessage());
    }

    @Test
    void bailMatchesSerialSemantics() {
        Context root = Context.root();
        root.on("gate", args -> null);
        root.on("gate", args -> "STOP");

        assertEquals("STOP", root.bail("gate", null));
    }

    @Test
    void waterfallWrapsTerminalResultOutermostFirst() {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        root.on("pipeline", (WaterfallListener<String, Integer>) (args, next) -> {
            log.add("outer-in");
            Integer r = next.invoke(args);
            log.add("outer-out");
            return r + 1;
        });
        root.on("pipeline", (WaterfallListener<String, Integer>) (args, next) -> {
            log.add("inner-in");
            Integer r = next.invoke(args);
            log.add("inner-out");
            return r * 2;
        });

        Integer result = root.waterfall("pipeline", "x", args -> 10);

        // 先注册者为最外层：outer(inner(terminal))，terminal 10 → inner*2=20 → outer+1=21
        assertEquals(21, result);
        assertEquals(List.of("outer-in", "inner-in", "inner-out", "outer-out"), log);
    }

    @Test
    void waterfallModifiesArgsDownward() {
        Context root = Context.root();
        root.on("pipeline", (WaterfallListener<String, String>) (args, next) -> next.invoke(args + "-外层改写"));
        root.on("pipeline", (WaterfallListener<String, String>) (args, next) -> next.invoke(args + "-内层改写"));

        String result = root.waterfall("pipeline", "原始", args -> "收到:" + args);

        assertEquals("收到:原始-外层改写-内层改写", result);
    }

    @Test
    void waterfallVetoSkipsInnerLayersAndTerminal() {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        root.on("pipeline", (WaterfallListener<String, String>) (args, next) -> {
            log.add("外层否决");
            return "替代结果";
        });
        root.on("pipeline", (WaterfallListener<String, String>) (args, next) -> {
            log.add("不应执行");
            return next.invoke(args);
        });

        String result = root.waterfall("pipeline", "x", args -> {
            log.add("终端也不应执行");
            return "terminal";
        });

        assertEquals("替代结果", result);
        assertEquals(List.of("外层否决"), log);
    }

    @Test
    void waterfallWithoutListenersRunsTerminalDirectly() {
        Context root = Context.root();

        Integer result = root.waterfall("empty", "x", args -> 42);

        assertEquals(42, result);
    }

    @Test
    void waterfallWrapsErrorForCaller() {
        Context root = Context.root();
        root.on("pipeline", (WaterfallListener<String, String>) (args, next) -> {
            throw new IllegalStateException("中间层炸了");
        });

        PluginException e = assertThrows(PluginException.class,
                () -> root.waterfall("pipeline", "x", args -> "t"));
        assertEquals("中间层炸了", e.getCause().getMessage());
    }

    @Test
    void listenerRemovedWhenPluginScopeDisposed() {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        root.on("tick", recorder(log, "宿主自己的监听器", null));

        PluginHandle plugin = root.plugin(new Plugin<Void>() {
            @Override
            public Class<Void> configType() {
                return null;
            }

            @Override
            public Disposable apply(Context ctx, Void config) {
                return ctx.on("tick", recorder(log, "插件的监听器", null));
            }
        }, null);

        root.emit("tick", null);
        plugin.dispose();
        root.emit("tick", null);

        // 插件停止后其监听器自动摘除；宿主作用域注册的不受影响
        assertEquals(List.of("宿主自己的监听器", "插件的监听器", "宿主自己的监听器"), log);
    }

    @Test
    void plainAndWaterfallListenersAreIndependentTables() {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        root.on("mixed", recorder(log, "普通", null));
        root.on("mixed", (WaterfallListener<String, String>) (args, next) -> {
            log.add("瀑布");
            return next.invoke(args);
        });

        root.emit("mixed", null);

        // emit 只消费普通表；瀑布监听器只在 waterfall 派发时进入
        assertEquals(List.of("普通"), log);
    }

    @Test
    void disposedScopeRejectsOn() {
        Context root = Context.root();
        root.dispose();

        assertThrows(PluginException.class, () -> root.on("tick", args -> null));
    }

    @Test
    void nullArgumentsFailFast() {
        Context root = Context.root();

        assertThrows(NullPointerException.class, () -> root.on(null, args -> null));
        assertThrows(NullPointerException.class, () -> root.on("tick", (EventListener) null));
        assertThrows(NullPointerException.class, () -> root.emit(null, null));
        assertThrows(NullPointerException.class, () -> root.parallel(null, null));
        assertThrows(NullPointerException.class, () -> root.serial(null, null));
        assertThrows(NullPointerException.class, () -> root.bail(null, null));
        assertThrows(NullPointerException.class, () -> root.waterfall(null, "x", args -> "t"));
        assertThrows(NullPointerException.class, () -> root.waterfall("pipeline", "x", null));
    }

    @Test
    void serialAllAbstainReturnsNull() {
        Context root = Context.root();
        root.on("vote", args -> null);

        assertNull(root.serial("vote", null));
        assertFalse(Boolean.TRUE.equals(root.serial("vote", null)));
    }

    @Test
    void waterfallDispatchDoesNotInvokePlainListeners() {
        Context root = Context.root();
        List<String> log = new ArrayList<>();
        // 同名事件挂两种监听器
        root.on("mixed", args -> { log.add("普通监听器被执行"); return null; });
        root.on("mixed", (WaterfallListener<String, String>) (args, next) -> {
            log.add("瀑布监听器被执行");
            return next.invoke(args);
        });

        // waterfall 派发：只走瀑布表
        root.waterfall("mixed", "payload", args -> "终端结果");

        // 断言：瀑布监听器执行了、普通监听器没执行（两张表互不可见）
        assertEquals(List.of("瀑布监听器被执行"), log,
                "waterfall 只查瀑布表，普通监听器不应被调用");
    }
}
