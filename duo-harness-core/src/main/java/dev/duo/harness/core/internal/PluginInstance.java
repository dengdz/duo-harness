package dev.duo.harness.core.internal;

import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.PluginState;
import dev.duo.harness.core.api.PluginStatus;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 插件实例：一次插件加载的运行期实体——epoch 依赖指纹驱动六态迁移。
 *
 * <p>epoch = 依赖服务实例集合的指纹（服务名排序 + 实例 identity 拼串）。
 * 指纹变化（依赖消失/回归/换实现）触发复查：PENDING 且齐 → 启动；
 * ACTIVE 且指纹变 → 卸载回 PENDING 后继续复查（服务回归即自动重启）。
 * 这同时系统性化解了"注销传导误停刚被新实例唤醒的依赖方"的竞态窗口：
 * 误停实为一次多余重启，最终一致。</p>
 *
 * <p>状态迁移统一"锁内改态、锁外广播 plugin/status 事件"；
 * scope 在每次启动时新建（ContextImpl 一次性），卸载即整体回滚。</p>
 */
final class PluginInstance {

    /**
     * 本实例的私有作用域（apply 的 ctx；每次启动新建，卸载即回滚其全部副作用）。
     * volatile：重建与置空跨线程可见。
     */
    private volatile ContextImpl scope;
    /** 插件实例登记簿：dispose 时自注销、供服务变化传导定位。 */
    private final PluginRegistry registry;
    /** 事件总线（状态迁移广播；全树共享）。 */
    private final EventsImpl events;
    /** 服务注册表（epoch 计算的读取源；全树共享）。 */
    private final ServiceRegistry services;
    /** 插件描述（apply 与 configType 的来源）。 */
    private final Plugin<?> plugin;
    /** 绑定完成的配置（重启复用同一份）。 */
    private final Object config;
    /** 诊断显示名（报错点名用）。 */
    private final String pluginName;
    /** 插件声明的依赖服务名集合（TreeSet：epoch 拼串顺序稳定）。 */
    private final TreeSet<String> inject;
    /** 首次启动完成信号（激活或失败都放行等待者；重启经事件观测）。 */
    private final CountDownLatch started = new CountDownLatch(1);
    /** 状态锁。不用 synchronized：与内核锁惯例一致，虚拟线程不 pin（ADR-0002）。 */
    private final ReentrantLock lock = new ReentrantLock();

    /** 生命周期状态；仅在锁内读写。 */
    private PluginState state = PluginState.PENDING;
    /** 启动失败原因（经 awaitStartup 重抛）；volatile 供等待者读。 */
    private volatile Exception failure;
    /** 激活时冻结的依赖指纹；ACTIVE 期间据此判变。 */
    private String epoch;
    /** LOADING 期间又发生依赖变化：apply 完成后须立即复查（同线程传导兜底）。 */
    private boolean dirtyDuringLoading;

    PluginInstance(PluginRegistry registry, EventsImpl events, ServiceRegistry services,
                   Plugin<?> plugin, Object config, String pluginName, Set<String> inject) {
        this.registry = registry;
        this.events = events;
        this.services = services;
        this.plugin = plugin;
        this.config = config;
        this.pluginName = pluginName;
        this.inject = new TreeSet<>(inject);
        this.scope = newScope();
    }

    /** 新建私有作用域（构造与每次重启共用）。 */
    private ContextImpl newScope() {
        return new ContextImpl(events, services, registry, inject);
    }

    /** 诊断显示名（报错点名用）。 */
    String pluginName() {
        return pluginName;
    }

    /** 插件声明的依赖服务名集合（防御拷贝，防外部变更指纹输入）。 */
    Set<String> inject() {
        return java.util.Collections.unmodifiableSet(inject);
    }

    /** 启动失败原因；未失败返回 null。 */
    Exception failure() {
        return failure;
    }

    /** 当前状态（锁内快照）。 */
    PluginState state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 依赖指纹复查：服务提供/注销传导与初次加载的统一入口。
     * 循环推进直至稳定（卸载后依赖又齐 → 原地重启一轮）。
     */
    void recheck() {
        while (true) {
            String target;
            PluginState from;
            lock.lock();
            try {
                target = computeEpochLocked();
                if (state == PluginState.PENDING) {
                    if (target == null) {
                        return; // 继续等
                    }
                    from = state;
                    state = PluginState.LOADING;
                } else if (state == PluginState.ACTIVE) {
                    if (target != null && target.equals(epoch)) {
                        return; // 指纹未变
                    }
                    from = state;
                    state = PluginState.UNLOADING;
                } else if (state == PluginState.LOADING) {
                    dirtyDuringLoading = true; // apply 期间的传导，稍后复查
                    return;
                } else {
                    return; // FAILED / DISPOSED / UNLOADING：终态或他人正在迁移
                }
            } finally {
                lock.unlock();
            }
            broadcast(from, state());

            if (state() == PluginState.UNLOADING) {
                unloadActiveScope();
                // 条件迁移：若并发 dispose 已推进终态，不得把 DISPOSED 复活成 PENDING
                if (!settleIfCurrent(PluginState.UNLOADING, PluginState.PENDING)) {
                    return;
                }
                broadcast(PluginState.UNLOADING, PluginState.PENDING);
                continue;
            }
            if (!runApplyAndSettle(target)) {
                return; // FAILED / 中途被销毁
            }
            lock.lock();
            boolean recheckAgain;
            try {
                recheckAgain = dirtyDuringLoading;
                dirtyDuringLoading = false;
            } finally {
                lock.unlock();
            }
            if (!recheckAgain) {
                return;
            }
        }
    }

    /** dispose 入口（handle.dispose 与级联回滚共用；幂等）。 */
    void dispose() {
        PluginState from;
        lock.lock();
        try {
            if (state == PluginState.DISPOSED) {
                return;
            }
            from = state;
            state = PluginState.UNLOADING;
        } finally {
            lock.unlock();
        }
        started.countDown();
        registry.unregister(this);
        unloadActiveScope();
        settleIfCurrent(PluginState.UNLOADING, PluginState.DISPOSED);
        // 卸载与销毁两段迁移都对外广播，起点取进入 UNLOADING 之前的状态
        broadcast(from, PluginState.UNLOADING);
        broadcast(PluginState.UNLOADING, PluginState.DISPOSED);
    }

    /** 等待首次启动完成：PENDING 阻塞至激活或失败（虚拟线程友好）。 */
    void await() {
        try {
            started.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluginException("等待插件 " + pluginName + " 启动被中断", e);
        }
        rethrowFailureIfAny();
    }

    /** 仅供日志与诊断输出。 */
    @Override
    public String toString() {
        return pluginName + "[" + state() + "]";
    }

    /** 状态广播（锁外调用；emit 自带监听器异常隔离）。 */
    private void broadcast(PluginState from, PluginState to) {
        events.emit(PluginStatus.EVENT, new PluginStatus(pluginName, from, to));
    }

    /** apply 执行与收态；返回是否继续复查循环。 */
    private boolean runApplyAndSettle(String target) {
        if (scope == null) {
            scope = newScope(); // 重启路径：上次卸载已置空，apply 需要新作用域
        }
        try {
            Disposable overall = runApply();
            lock.lock();
            try {
                if (state != PluginState.LOADING) {
                    // apply 期间被外部 dispose/停止：scope 已由对方回滚，尊重其迁移
                    scope = null;
                    started.countDown();
                    return false;
                }
                if (overall != null) {
                    scope.effect(overall);
                }
                epoch = target;
                state = PluginState.ACTIVE;
            } finally {
                lock.unlock();
            }
            started.countDown();
            broadcast(PluginState.LOADING, PluginState.ACTIVE);
            return true;
        } catch (Exception e) {
            markFailed(e);
            started.countDown();
            return false;
        }
    }

    /**
     * 条件置态：仅当当前仍处 expected 态才迁移到 to。
     * 防并发场景下终态被覆盖（如 dispose 已推进 DISPOSED 后被 recheck 复活成 PENDING）。
     *
     * @return 是否实际发生迁移
     */
    private boolean settleIfCurrent(PluginState expected, PluginState to) {
        lock.lock();
        try {
            if (state != expected) {
                return false;
            }
            state = to;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** 卸载当前 scope（逆序回滚全部副作用，清理错误只记日志）并清指纹。 */
    private void unloadActiveScope() {
        ContextImpl current = scope;
        scope = null;
        epoch = null;
        if (current != null) {
            current.disposeQuietly();
        }
    }

    @SuppressWarnings("unchecked")
    private Disposable runApply() throws Exception {
        return ((Plugin<Object>) plugin).apply(scope, config);
    }

    /** 依赖指纹：服务名排序后按 (名, 实例 identity) 拼串；任一缺失返回 null。 */
    private String computeEpochLocked() {
        StringBuilder sb = new StringBuilder();
        for (String name : inject) {
            Object impl = services.resolve(name);
            if (impl == null) {
                return null;
            }
            // 类名 + identity 双因子：单因子碰撞会漏判换实现（epoch 不变）
            sb.append(name).append('=').append(impl.getClass().getName())
                    .append('@').append(System.identityHashCode(impl)).append(';');
        }
        return sb.toString();
    }

    /** 启动失败收尾：FAILED 终态 + 回滚半启动 scope + 广播。 */
    private void markFailed(Exception error) {
        failure = error;
        // 条件迁移：apply 期间若被并发 dispose 抢先迁移，尊重其终态，不覆盖
        boolean marked = settleIfCurrent(PluginState.LOADING, PluginState.FAILED);
        ContextImpl current = scope;
        scope = null;
        if (current != null) {
            current.disposeQuietly();
        }
        if (marked) {
            broadcast(PluginState.LOADING, PluginState.FAILED);
        }
    }

    private void rethrowFailureIfAny() {
        Exception error = failure;
        if (error != null) {
            throw new PluginException("插件 " + pluginName + " 启动失败", error);
        }
    }
}
