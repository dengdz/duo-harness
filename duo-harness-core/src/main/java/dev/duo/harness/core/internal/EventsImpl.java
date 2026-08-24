package dev.duo.harness.core.internal;

import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.events.EventListener;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.events.WaterfallListener;
import dev.duo.harness.core.api.events.WaterfallNext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件总线实现：监听器表挂在根作用域、全树共享——任意子作用域注册的
 * 监听器对任意作用域的派发可见（支撑工具管线类跨插件拦截）。
 *
 * <p>普通监听器与瀑布监听器按事件名分表存储，互不干扰；
 * 表用 CopyOnWriteArrayList：派发遍历不持锁，注册/摘除与派发可并发。</p>
 */
final class EventsImpl {

    private static final Logger log = LoggerFactory.getLogger(EventsImpl.class);

    /** 虚拟线程名前缀：事件名 + 序号拼接（thread dump 可区分监听器）。 */
    private static final String THREAD_NAME_PREFIX = "duo-event-";

    /** 普通监听器表：emit/parallel/serial/bail 派发消费。值实为 CopyOnWriteArrayList。 */
    private final Map<String, List<EventListener>> listeners = new ConcurrentHashMap<>();
    /** 瀑布监听器表：waterfall 派发消费；与普通表分立，避免形状错配。 */
    private final Map<String, List<WaterfallListener<Object, Object>>> waterfallListeners =
            new ConcurrentHashMap<>();

    /** 普通监听器入表，返回幂等摘除器。 */
    Disposable add(String event, EventListener listener) {
        List<EventListener> list = listeners.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return () -> list.remove(listener);
    }

    /** 瀑布监听器入表（擦除为 Object 形状），返回幂等摘除器。 */
    @SuppressWarnings("unchecked")
    <T, R> Disposable addWaterfall(String event, WaterfallListener<T, R> listener) {
        List<WaterfallListener<Object, Object>> list =
                waterfallListeners.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>());
        list.add((WaterfallListener<Object, Object>) listener);
        return () -> list.remove(listener);
    }

    /** 广播派发：异常隔离（warn 日志），不传染兄弟与派发方。 */
    void emit(String event, Object args) {
        for (EventListener listener : listeners.getOrDefault(event, List.of())) {
            try {
                listener.on(args);
            } catch (Exception e) {
                // 广播隔离：坏监听器不传染兄弟、不打断派发方
                log.warn("事件 {} 的监听器 {} 抛错（已隔离）",
                        event, listener.getClass().getName(), e);
            }
        }
    }

    /** 并发派发：每监听器一虚拟线程，失败聚合为主异常 + suppressed。 */
    void parallel(String event, Object args) {
        List<EventListener> all = listeners.getOrDefault(event, List.of());
        if (all.isEmpty()) {
            return;
        }
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>(all.size()));
        List<Thread> workers = new ArrayList<>(all.size());
        int index = 0;
        for (EventListener listener : all) {
            // 每个监听器一个虚拟线程（ADR-0002 并发模型）；序号入名，thread dump 可区分
            String workerName = THREAD_NAME_PREFIX + event + "-" + index++;
            workers.add(Thread.ofVirtual().name(workerName).unstarted(() -> {
                try {
                    listener.on(args);
                } catch (Exception e) {
                    errors.add(e);
                }
            }));
        }
        workers.forEach(Thread::start);
        for (Thread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PluginException("事件 " + event + " 并发派发被中断", e);
            }
        }
        if (!errors.isEmpty()) {
            PluginException failure =
                    new PluginException("事件 " + event + " 的监听器并发执行失败", errors.getFirst());
            for (int i = 1; i < errors.size(); i++) {
                failure.addSuppressed(errors.get(i));
            }
            throw failure;
        }
    }

    /** 顺序投票：首个非 null 返回值即终值并终止链；监听器异常包装上抛。 */
    Object serial(String event, Object args) {
        for (EventListener listener : listeners.getOrDefault(event, List.of())) {
            Object result;
            try {
                result = listener.on(args);
            } catch (PluginException e) {
                throw e;
            } catch (Exception e) {
                throw new PluginException("事件 " + event + " 的顺序投票被监听器打断", e);
            }
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /** 瀑布派发：从终端逆序包装洋葱链，先注册者最外层；不调 next 即否决。 */
    @SuppressWarnings("unchecked")
    <T, R> R waterfall(String event, T args, WaterfallNext<T, R> terminal) {
        List<WaterfallListener<Object, Object>> layers =
                waterfallListeners.getOrDefault(event, List.of());
        // 从终端向外逆序包装：先注册者最外层（DSH 同序）。链内部统一 Object 形状，
        // 类型安全由事件名的调用约定保证（注册与派发使用一致的 T/R）。
        WaterfallNext<Object, Object> chain = (WaterfallNext<Object, Object>) terminal;
        for (int i = layers.size() - 1; i >= 0; i--) {
            WaterfallListener<Object, Object> layer = layers.get(i);
            WaterfallNext<Object, Object> inner = chain;
            chain = a -> layer.invoke(a, inner);
        }
        try {
            return (R) chain.invoke(args);
        } catch (PluginException e) {
            throw e;
        } catch (Exception e) {
            throw new PluginException("事件 " + event + " 的瀑布管线执行失败", e);
        }
    }
}
