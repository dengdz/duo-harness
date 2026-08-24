package dev.duo.harness.core.internal;

import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginException;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 插件实例：一次插件加载的运行期实体——持有私有作用域、依赖集与生命周期状态。
 *
 * <p>三态（PENDING / ACTIVE / STOPPED）是依赖驱动启停的最小状态机；
 * 完整六态与 epoch 重载（服务回归自动重启、换实现重启）由后续工单扩展。</p>
 */
final class PluginInstance {

    /** 实例状态：PENDING 等依赖、ACTIVE 已激活、STOPPED 已停止（终态）。 */
    enum State { PENDING, ACTIVE, STOPPED }

    /** 本实例的私有作用域（apply 的 ctx；销毁即回滚其全部副作用）。 */
    private final ContextImpl scope;
    /** 插件实例登记簿：dispose 时自注销、供服务变化传导定位。 */
    private final PluginRegistry registry;
    /** 插件描述（apply 与 configType 的来源）。 */
    private final Plugin<?> plugin;
    /** 绑定完成的配置（startOrDefer 时传给 apply）。 */
    private final Object config;
    /** 诊断显示名（报错点名用）。 */
    private final String pluginName;
    /** 插件声明的依赖服务名集合（不可变）。 */
    private final Set<String> inject;
    /** 启动完成信号（激活或失败都放行等待者）。 */
    private final CountDownLatch started = new CountDownLatch(1);
    /** 状态锁。不用 synchronized：与内核锁惯例一致，虚拟线程不 pin（ADR-0002）。 */
    private final ReentrantLock lock = new ReentrantLock();

    /** 生命周期状态；仅在锁内读写。 */
    private State state = State.PENDING;
    /** 启动失败原因（异步路径经 awaitStartup 重抛）；volatile 供等待者读。 */
    private volatile Exception failure;

    PluginInstance(PluginRegistry registry, ContextImpl scope, Plugin<?> plugin, Object config,
                   String pluginName, Set<String> inject) {
        this.registry = registry;
        this.scope = scope;
        this.plugin = plugin;
        this.config = config;
        this.pluginName = pluginName;
        this.inject = inject;
    }

    /** 诊断显示名（报错点名用）。 */
    String pluginName() {
        return pluginName;
    }

    /** 插件声明的依赖服务名集合。 */
    Set<String> inject() {
        return inject;
    }

    /** 启动失败原因（同步路径重抛用）；未失败返回 null。 */
    Exception failure() {
        return failure;
    }

    /** 当前状态（锁内快照）。 */
    State state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    /** 依赖是否全部就绪（PENDING 判定）。 */
    boolean missingDependencies(ServiceRegistry services) {
        for (String name : inject) {
            if (services.resolve(name) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 启动或继续挂起：依赖齐则跑 apply（失败记 failure 返回 false），
     * 有缺失则保持 PENDING。由 plugin() 同步路径与 provide 唤醒路径共用。
     */
    boolean startOrDefer(ServiceRegistry services) {
        lock.lock();
        try {
            if (state != State.PENDING) {
                return state == State.ACTIVE;
            }
            if (missingDependencies(services)) {
                return true; // 挂起不是失败
            }
            state = State.ACTIVE; // 先置态再跑 apply：apply 内的级联加载可见父态
        } finally {
            lock.unlock();
        }
        try {
            Disposable overall = runApply();
            if (overall != null) {
                scope.effect(overall);
            }
            started.countDown();
            return true;
        } catch (Exception e) {
            markStopped(e);
            scope.disposeQuietly();
            started.countDown();
            return false;
        }
    }

    /** 服务注销传导：依赖集含该服务的活跃实例停止（幂等）。 */
    void stop() {
        lock.lock();
        try {
            if (state != State.ACTIVE) {
                return;
            }
            state = State.STOPPED;
        } finally {
            lock.unlock();
        }
        scope.disposeQuietly();
        started.countDown();
    }

    /** 停止并从登记簿移除（handle.dispose 与级联回滚共用入口；幂等）。 */
    void dispose() {
        lock.lock();
        try {
            state = State.STOPPED;
        } finally {
            lock.unlock();
        }
        started.countDown();
        registry.unregister(this);
        scope.disposeQuietly();
    }

    /** 等待启动完成：PENDING 阻塞至激活或失败（虚拟线程友好）。 */
    void await() {
        try {
            started.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluginException("等待插件 " + pluginName + " 启动被中断", e);
        }
        rethrowFailureIfAny();
    }

    /** 带超时等待（诊断入口）。 */
    State await(long timeout, TimeUnit unit) throws InterruptedException {
        started.await(timeout, unit);
        return state();
    }

    /** 仅供日志与诊断输出。 */
    @Override
    public String toString() {
        return pluginName + "[" + state() + "]";
    }

    @SuppressWarnings("unchecked")
    private Disposable runApply() throws Exception {
        return ((Plugin<Object>) plugin).apply(scope, config);
    }

    /** 启动失败收尾：置终态并记录原因。 */
    private void markStopped(Exception error) {
        lock.lock();
        try {
            state = State.STOPPED;
            failure = error;
        } finally {
            lock.unlock();
        }
    }

    private void rethrowFailureIfAny() {
        Exception error = failure;
        if (error != null) {
            throw new PluginException("插件 " + pluginName + " 启动失败", error);
        }
    }
}
