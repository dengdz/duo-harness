package dev.duo.harness.core.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 插件实例登记簿：挂根作用域、全树共享。
 *
 * <p>承担服务可用性变化的传导：provide 后唤醒挂起实例、注销后停止依赖方
 * 实例——epoch 重载（服务回归自动重启）由后续工单在此扩展。
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

    /** 服务就绪：唤醒依赖该服务的挂起实例（startOrDefer 自查其余依赖）。 */
    void onServiceAvailable(ServiceRegistry services, String name) {
        for (PluginInstance instance : instances) {
            if (instance.state() == PluginInstance.State.PENDING && instance.inject().contains(name)) {
                boolean started = instance.startOrDefer(services);
                if (!started) {
                    log.warn("服务 {} 就绪后唤醒插件 {} 失败（错误经 awaitStartup 抛出）",
                            name, instance.pluginName());
                }
            }
        }
    }

    /** 服务注销：停止依赖该服务的活跃实例；失败只记日志，不影响兄弟实例。 */
    void onServiceUnavailable(String name) {
        for (PluginInstance instance : instances) {
            if (instance.state() == PluginInstance.State.ACTIVE && instance.inject().contains(name)) {
                log.info("服务 {} 注销，停止依赖它的插件 {}", name, instance.pluginName());
                instance.stop();
            }
        }
    }
}
