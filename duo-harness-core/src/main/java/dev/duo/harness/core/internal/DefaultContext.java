package dev.duo.harness.core.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginConfigException;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.PluginHandle;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Context 的默认实现：一个实例 = 一个插件实例的作用域。
 *
 * <p>用 ReentrantLock 而非 synchronized 保护副作用栈——虚拟线程模型下
 * synchronized 会 pin 载体线程（ADR-0002 后果条款）。</p>
 */
public final class DefaultContext implements Context {

    /**
     * 严格绑定：config record 缺字段即失败（错误前移到加载时刻，ADR-0003）。
     * Jackson 默认容忍缺失（引用类型补 null、原始类型补 0），会掩盖配置错误。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);

    private final ReentrantLock lock = new ReentrantLock();
    /** 副作用栈：注册序入栈，销毁时逆序弹出。 */
    private final Deque<RegisteredDisposable> effects = new ArrayDeque<>();
    private final AtomicBoolean disposed = new AtomicBoolean();

    @Override
    public <C> PluginHandle plugin(Plugin<C> plugin, Object rawConfig) {
        Objects.requireNonNull(plugin, "plugin");
        if (disposed.get()) {
            throw new PluginException("作用域已销毁，拒绝加载插件 " + plugin.getClass().getName());
        }
        String pluginName = plugin.getClass().getName();
        C config = bindConfig(plugin, rawConfig, pluginName);

        DefaultContext child = new DefaultContext();
        try {
            Disposable overall = plugin.apply(child, config);
            if (overall != null) {
                child.effect(overall);
            }
            // 挂载进父作用域也可能失败（父被并发销毁时 effect 拒绝）——
            // 已启动的 child 必须回滚，否则成为无人回收的泄漏实例
            effect(child::dispose);
        } catch (PluginException e) {
            child.disposeQuietly();
            throw e;
        } catch (Exception e) {
            child.disposeQuietly();
            throw new PluginException("插件 " + pluginName + " 启动失败", e);
        }
        return new PluginHandleImpl(child);
    }

    @Override
    public Disposable effect(Disposable disposer) {
        // null 副作用若放行，NPE 会被推迟到回滚期并埋进聚合异常，根因难定位
        Objects.requireNonNull(disposer, "disposer");
        RegisteredDisposable registered = new RegisteredDisposable(disposer);
        lock.lock();
        try {
            // 检查必须在锁内：否则"已销毁仍注册"的副作用会压进永不回滚的栈，静默丢失
            if (disposed.get()) {
                throw new PluginException("作用域已销毁，拒绝注册副作用");
            }
            effects.push(registered);
        } finally {
            lock.unlock();
        }
        return registered::disposeOnce;
    }

    @Override
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        PluginException failure = null;
        while (true) {
            RegisteredDisposable registered;
            lock.lock();
            try {
                registered = effects.pollFirst();
            } finally {
                lock.unlock();
            }
            if (registered == null) {
                break;
            }
            // 兄弟副作用的失败互不掩盖：首个作为主异常，其余挂 suppressed
            try {
                registered.disposeOnce();
            } catch (Exception e) {
                if (failure == null) {
                    failure = new PluginException("作用域销毁时副作用回滚失败", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** 启动失败路径的清理：回滚错误吞掉——原始启动异常才是调用方要看到的主错误。 */
    private void disposeQuietly() {
        try {
            dispose();
        } catch (PluginException ignored) {
            // 主错误（启动失败）已在异常链上，清理错误不再叠加以免掩盖
        }
    }

    private static <C> C bindConfig(Plugin<C> plugin, Object rawConfig, String pluginName) {
        Class<C> configType = plugin.configType();
        if (rawConfig == null) {
            if (configType == null) {
                return null;
            }
            // 声明了 config 类型就必须提供配置——静默传 null 会让插件在 apply 深处 NPE，报错远离根因
            throw new PluginConfigException(
                    "插件 " + pluginName + " 声明了 config 类型 " + configType.getName() + "，却未提供配置", null);
        }
        if (configType == null) {
            throw new PluginConfigException(
                    "插件 " + pluginName + " 不接受配置（configType 为 null），却收到了 rawConfig", null);
        }
        try {
            return MAPPER.convertValue(rawConfig, configType);
        } catch (IllegalArgumentException e) {
            throw new PluginConfigException(
                    "插件 " + pluginName + " 配置绑定失败: " + bindingPath(e) + ": " + e.getMessage(), e);
        }
    }

    /** 提取 Jackson 绑定错误的字段路径，供点名式报错定位到行内字段。 */
    private static String bindingPath(IllegalArgumentException e) {
        if (e.getCause() instanceof MismatchedInputException mismatched) {
            String reference = mismatched.getPathReference();
            return reference == null || reference.isEmpty() ? "<根>" : reference;
        }
        return "<未知位置>";
    }

    /** 单个副作用的幂等移除器：只在栈中存在时执行一次。 */
    private static final class RegisteredDisposable {

        private final Disposable disposer;
        private final AtomicBoolean done = new AtomicBoolean();

        RegisteredDisposable(Disposable disposer) {
            this.disposer = disposer;
        }

        void disposeOnce() throws Exception {
            if (done.compareAndSet(false, true)) {
                disposer.dispose();
            }
        }
    }
}
