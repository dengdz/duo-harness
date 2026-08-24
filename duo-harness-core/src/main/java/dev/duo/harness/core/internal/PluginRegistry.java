package dev.duo.harness.core.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 插件实例登记簿：挂根作用域、全树共享。
 *
 * <p>服务提供/注销的传导统一为"依赖该服务的实例复查 epoch 指纹"——
 * 指纹变化驱动实例在六态间迁移（启动/卸载/重启），取代盲目的
 * 单向停止，从根本上化解注销与新注册竞态中的误停窗口。
 * CopyOnWriteArrayList 的快照迭代允许传导过程中注册新实例（apply 内级联加载）。</p>
 */
final class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    /** 全部插件实例（含挂起与活跃）；CoW 快照迭代支持传导中注册新实例。 */
    private final List<PluginInstance> instances = new CopyOnWriteArrayList<>();

    void register(PluginInstance instance) {
        instances.add(instance);
    }

    void unregister(PluginInstance instance) {
        instances.remove(instance);
    }

    /** 服务就绪：依赖该服务的实例复查（PENDING 者据此启动）。 */
    void onServiceAvailable(String name) {
        recheckDependentsOf(name);
    }

    /** 服务注销：依赖该服务的实例复查（ACTIVE 者据此卸载回 PENDING）。 */
    void onServiceUnavailable(String name) {
        recheckDependentsOf(name);
    }

    private void recheckDependentsOf(String name) {
        for (PluginInstance instance : instances) {
            if (instance.inject().contains(name)) {
                try {
                    instance.recheck();
                } catch (RuntimeException e) {
                    // 单实例传导异常不阻断兄弟实例的复查
                    log.warn("服务 {} 变化后复查插件 {} 异常", name, instance.pluginName(), e);
                }
            }
        }
    }
}
