package dev.duo.harness.core.internal;

import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginException;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 插件实例：一次插件加载的运行期实体——持有私有作用域、依赖集与生命周期状态。
 *
 * <p>三态（PENDING / ACTIVE / STOPPED）是依赖驱动启停的最小状态机；
 * 完整六态与 epoch 重载（服务回归自动重启、换实现重启）由后续工单扩展。</p>
 */
final class PluginInstance {

    /** 实例状态：PENDING 等依赖、ACTIVE 已激活、STOPPED 已停止（终态）。 */
    enum State { PENDING, ACTIVE, STOPPED }

    private final ContextImpl scope;
    private final PluginRegistry registry;
    private final Plugin<?> plugin;
    private final Object config;
    private final String pluginName;
    /** 插件声明的依赖服务名集合（不可变）。 */
    private final Set<String> inject;
    /** 启动完成信号（激活或失败都放行等待者）。 */
    private final CountDownLatch started = new CountDownLatch(1);

    private State state = State.PENDING;
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

    String pluginName() {
        return pluginName;
    }

    Set<String> inject() {
        return inject;
    }

    /** 启动失败原因（同步路径重抛用）；未失败返回 null。 */
    Exception failure() {
        return failure;
    }

    State state() {
        synchronized (this) {
            return state;
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
        synchronized (this) {
            if (state != State.PENDING) {
                return state == State.ACTIVE;
            }
            if (missingDependencies(services)) {
                return true; // 挂起不是失败
            }
            state = State.ACTIVE; // 先置态再跑 apply：apply 内的级联加载可见父态
        }
        try {
            Disposable overall = runApply();
            if (overall != null) {
                scope.effect(overall);
            }
            started.countDown();
            return true;
        } catch (Exception e) {
            synchronized (this) {
                state = State.STOPPED;
                failure = e;
            }
            scope.disposeQuietly();
            started.countDown();
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Disposable runApply() throws Exception {
        return ((Plugin<Object>) plugin).apply(scope, config);
    }

    /** 服务注销传导：依赖集含该服务的活跃实例停止（幂等）。 */
    void stop() {
        if (state() != State.ACTIVE) {
            return;
        }
        synchronized (this) {
            if (state != State.ACTIVE) {
                return;
            }
            state = State.STOPPED;
        }
        scope.disposeQuietly();
        started.countDown();
    }

    /** 停止并从登记簿移除（handle.dispose 与级联回滚共用入口；幂等）。 */
    void dispose() {
        synchronized (this) {
            state = State.STOPPED;
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

    private void rethrowFailureIfAny() {
        Exception error = failure;
        if (error != null) {
            throw new PluginException("插件 " + pluginName + " 启动失败", error);
        }
    }

    /** 仅供日志与诊断输出。 */
    @Override
    public String toString() {
        return pluginName + "[" + state() + "]";
    }
}
