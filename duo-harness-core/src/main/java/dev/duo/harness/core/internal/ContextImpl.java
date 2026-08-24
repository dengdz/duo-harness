package dev.duo.harness.core.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.EventListener;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginConfigException;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.WaterfallListener;
import dev.duo.harness.core.api.WaterfallNext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Context 的实现：一个实例 = 一个插件实例的作用域。
 *
 * <p>用 ReentrantLock 而非 synchronized 保护副作用栈——虚拟线程模型下
 * synchronized 会 pin 载体线程（ADR-0002 后果条款）。</p>
 */
public final class ContextImpl implements Context {

    private static final Logger log = LoggerFactory.getLogger(ContextImpl.class);

    /**
     * 严格绑定：config record 缺字段即失败（错误前移到加载时刻，ADR-0003）。
     * Jackson 默认容忍缺失（引用类型补 null、原始类型补 0），会掩盖配置错误。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);

    /** 副作用栈的互斥访问。不用 synchronized：虚拟线程下会 pin 载体线程。 */
    private final ReentrantLock lock = new ReentrantLock();
    /** 副作用栈：注册序入栈，销毁时逆序弹出。 */
    private final Deque<RegisteredDisposable> effects = new ArrayDeque<>();
    /** 销毁标记：CAS 保证 dispose 幂等；effect/plugin 据此拒绝注册。 */
    private final AtomicBoolean disposed = new AtomicBoolean();
    /** 事件总线：根创建、全树共享；子作用域经继承构造器获得同一实例。 */
    private final EventsImpl events;
    /** 服务注册表：根创建、全树共享（isolate 预留的二阶键在 ServiceKey）。 */
    private final ServiceRegistry services;
    /** 插件实例登记簿：根创建、全树共享，承担服务变化的传导。 */
    private final PluginRegistry pluginInstances;
    /** 本作用域的 inject 声明（插件实例的读取许可）；根作用域为 null = 不限制。 */
    private final Set<String> injectedServices;

    /**
     * 根作用域构造：创建共享事件总线、服务注册表与插件登记簿。
     * public 供 api 包 Context.root() 跨包创建。
     */
    public ContextImpl() {
        this.events = new EventsImpl();
        this.services = new ServiceRegistry();
        this.pluginInstances = new PluginRegistry();
        this.injectedServices = null;
    }

    /** 子作用域构造：继承根的共享设施，携带本插件的 inject 读取许可。 */
    ContextImpl(EventsImpl events, ServiceRegistry services,
                PluginRegistry pluginInstances, Set<String> injectedServices) {
        this.events = events;
        this.services = services;
        this.pluginInstances = pluginInstances;
        this.injectedServices = injectedServices;
    }

    @Override
    public <C> PluginHandle plugin(Plugin<C> plugin, Object rawConfig) {
        Objects.requireNonNull(plugin, "plugin");
        if (disposed.get()) {
            throw new PluginException("作用域已销毁，拒绝加载插件 " + plugin.getClass().getName());
        }
        String pluginName = plugin.getClass().getName();
        C config = bindConfig(plugin, rawConfig, pluginName);
        Set<String> inject = Objects.requireNonNull(plugin.inject(),
                "inject() 返回 null（无依赖请返回空集）");
        PluginInstance instance = new PluginInstance(
                this.pluginInstances, this.events, this.services, plugin, config, pluginName, inject);
        this.pluginInstances.register(instance);
        try {
            // 实例销毁作为本作用域副作用：父销毁级联停子（幂等）
            effect(instance::dispose);
        } catch (PluginException e) {
            instance.dispose();
            throw e;
        }
        instance.recheck();
        // 启动失败不在此抛：错误统一经 handle（awaitStartup 重抛 / state=FAILED）——
        // 同步与异步（唤醒）路径语义一致（DSH fiber 同款）
        return new PluginHandleImpl(instance);
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

    /**
     * 启动失败路径的清理：回滚错误只记 warn——
     * 原始启动异常才是调用方要看到的主错误。
     */
    void disposeQuietly() {
        try {
            dispose();
        } catch (PluginException e) {
            log.warn("启动失败后的回滚清理出错（主错误优先，不掩盖启动异常）", e);
        }
    }

    /**
     * 监听器入表后挂 effect；若作用域恰好并发销毁导致 effect 拒绝，
     * 补偿摘除防僵尸监听。
     */
    private Disposable registerRemoverAsEffect(Disposable remover) {
        try {
            return effect(remover);
        } catch (PluginException e) {
            try {
                remover.dispose();
            } catch (Exception cleanup) {
                log.warn("监听器补偿摘除失败", cleanup);
            }
            throw e;
        }
    }

    // === 服务 ===

    @Override
    public Disposable provide(String name, Object instance) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(instance, "instance");
        this.services.provide(name, instance);
        Disposable remover = () -> {
            // 值感知移除：并发下的新 provide 不被旧注销器误删
            if (this.services.remove(name, instance)) {
                this.pluginInstances.onServiceUnavailable(name);
            }
        };
        try {
            Disposable effectBound = effect(remover);
            // 先绑定 effect（防作用域并发销毁产生僵尸服务），再唤醒依赖方
            this.pluginInstances.onServiceAvailable(name);
            return effectBound;
        } catch (PluginException e) {
            try {
                remover.dispose();
            } catch (Exception cleanup) {
                log.warn("服务 {} 的补偿注销失败", name, cleanup);
            }
            throw e;
        }
    }

    @Override
    public <T> T as(Class<T> viewInterface) {
        Objects.requireNonNull(viewInterface, "viewInterface");
        if (!viewInterface.isInterface()) {
            throw new PluginException("as() 只接受接口类型，收到 " + viewInterface.getName());
        }
        return viewInterface.cast(Proxy.newProxyInstance(
                viewInterface.getClassLoader(),
                new Class<?>[] {viewInterface},
                this::invokeViewMethod));
    }

    /** 视图方法分发：Object 方法特判，其余按"方法名即服务名"寻址。 */
    private Object invokeViewMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "视图代理[" + injectedServicesDescription() + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> resolveService(method.getName(), method.getReturnType());
        };
    }

    /** 服务寻址三检查：inject 许可 → 注册表命中 → 类型兼容，全部点名报错。 */
    private Object resolveService(String name, Class<?> expectedType) {
        if (injectedServices != null && !injectedServices.contains(name)) {
            throw new PluginException("服务 \"" + name + "\" 未在 inject 中声明，拒绝读取"
                    + "（错误前移：请补充 inject 声明）");
        }
        Object instance = services.resolve(name);
        if (instance == null) {
            throw new PluginException("服务 \"" + name + "\" 未提供");
        }
        if (!expectedType.isInstance(instance)) {
            throw new PluginException("服务 \"" + name + "\" 的实例类型 "
                    + instance.getClass().getName() + " 与视图声明的返回类型 "
                    + expectedType.getName() + " 不兼容");
        }
        return instance;
    }

    private String injectedServicesDescription() {
        return injectedServices == null ? "根作用域" : String.join(",", injectedServices);
    }

    // === 事件 ===

    @Override
    public Disposable on(String event, EventListener listener) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(listener, "listener");
        Disposable remover = events.add(event, listener);
        return registerRemoverAsEffect(remover);
    }

    @Override
    public <T, R> Disposable on(String event, WaterfallListener<T, R> listener) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(listener, "listener");
        Disposable remover = events.addWaterfall(event, listener);
        return registerRemoverAsEffect(remover);
    }

    @Override
    public void emit(String event, Object args) {
        Objects.requireNonNull(event, "event");
        events.emit(event, args);
    }

    @Override
    public void parallel(String event, Object args) {
        Objects.requireNonNull(event, "event");
        events.parallel(event, args);
    }

    @Override
    public Object serial(String event, Object args) {
        Objects.requireNonNull(event, "event");
        return events.serial(event, args);
    }

    @Override
    public Object bail(String event, Object args) {
        Objects.requireNonNull(event, "event");
        return events.serial(event, args);
    }

    @Override
    public <T, R> R waterfall(String event, T args, WaterfallNext<T, R> terminal) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(terminal, "terminal");
        return events.waterfall(event, args, terminal);
    }

    private static <C> C bindConfig(Plugin<C> plugin, Object rawConfig, String pluginName) {
        Class<C> configType = plugin.configType();
        if (rawConfig == null) {
            if (configType == null) {
                return null;
            }
            // 声明了 config 类型就必须提供配置——静默传 null 会让插件在
            // apply 深处 NPE，报错远离根因
            throw new PluginConfigException(
                    "插件 " + pluginName + " 声明了 config 类型 " + configType.getName()
                            + "，却未提供配置");
        }
        if (configType == null) {
            throw new PluginConfigException(
                    "插件 " + pluginName + " 不接受配置（configType 为 null），却收到了 rawConfig");
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
