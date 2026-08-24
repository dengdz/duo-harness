package dev.duo.harness.core.internal;

import dev.duo.harness.core.api.PluginException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务注册表：全局单表，键为（服务名, 作用域）二阶结构（isolate 预留）。
 * 挂根作用域创建、全树共享。
 *
 * <p>provide / remove 的唤醒与停止传导由 {@link PluginRegistry} 负责，
 * 本类只管登记与寻址，避免两个关注点耦合。</p>
 */
final class ServiceRegistry {

    private final Map<ServiceKey, Object> services = new ConcurrentHashMap<>();

    /** 登记服务；同名（同作用域）冲突点名报错，保留先注册方。 */
    void provide(String name, Object instance) {
        ServiceKey key = ServiceKey.defaultScope(name);
        Object existing = services.putIfAbsent(key, instance);
        if (existing != null) {
            throw new PluginException(
                    "服务 \"" + name + "\" 已被注册（实例 " + existing.getClass().getName() + "），"
                            + "拒绝重复发布");
        }
    }

    /**
     * 值感知移除：仅当表内仍是本实例时才移除，避免旧实例的注销器
     * 在与新 provide 的竞态中删掉新实例并误停依赖方。
     */
    boolean remove(String name, Object instance) {
        return services.remove(ServiceKey.defaultScope(name), instance);
    }

    /** 寻址：未登记返回 null（由调用方决定报错语义）。 */
    Object resolve(String name) {
        return services.get(ServiceKey.defaultScope(name));
    }
}
