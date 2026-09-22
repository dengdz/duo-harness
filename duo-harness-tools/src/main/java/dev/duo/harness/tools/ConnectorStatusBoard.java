package dev.duo.harness.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 连接器状态板（M24 工单 05，ADR-0026 决策四）：外部连接器（现役 = MCP 服务器）
 * 的生命周期状态聚合——连接器侧 update 填充，呈现位订阅耗尽通知（注入收件箱，
 * 模型与用户可见），Web 状态面读快照标注不可用。
 *
 * <p>经视图接口消费（方法名 {@code connectorStatus()} 即服务名）。线程约定：
 * 全状态 synchronized 读写（低频更新、跨线程订阅），通知监听器在锁外回调。</p>
 */
public final class ConnectorStatusBoard {

    /** 服务名（camelCase——视图接口方法名即服务名，M23 记档口径）。 */
    public static final String SERVICE_NAME = "connectorStatus";

    /** 单个连接器的状态条目。 */
    public record Connector(String server, String state, String detail) {
    }

    private static final ConnectorStatusBoard SHARED = new ConnectorStatusBoard();

    /** server → 最新状态条目（TreeMap：快照按 server 名稳定排序）。 */
    private final Map<String, Connector> entries = new TreeMap<>();
    private final CopyOnWriteArrayList<Consumer<String>> gaveUpListeners = new CopyOnWriteArrayList<>();

    /** 插件装配用的共享实例（多连接行聚合同一块板）。 */
    public static ConnectorStatusBoard shared() {
        return SHARED;
    }

    /** 更新连接器状态（同 server 覆盖式——最新为准）。 */
    public synchronized void update(String server, String state, String detail) {
        entries.put(server, new Connector(server, state, detail));
    }

    /** 移除连接器条目（连接行停止/拔线时调用——防幽灵条目永驻状态面）。 */
    public synchronized void remove(String server) {
        entries.remove(server);
    }

    /** 快照（按 server 名排序的不可变列表）。 */
    public synchronized List<Connector> snapshot() {
        return List.copyOf(entries.values());
    }

    /** 订阅「预算耗尽」通知（呈现位接线用；CopyOnWriteArrayList 自身线程安全，重复订阅重复收到）。 */
    public void onGaveUp(Consumer<String> listener) {
        gaveUpListeners.add(java.util.Objects.requireNonNull(listener, "listener"));
    }

    /** 触发耗尽通知（连接器侧调用；监听器在锁外回调，避免回调重入死锁）。 */
    public void fireGaveUp(String notice) {
        for (Consumer<String> listener : gaveUpListeners) {
            listener.accept(notice);
        }
    }

    /** 视图桥接（方法名即服务名）：返回自身供消费方链式取快照/订阅。 */
    public ConnectorStatusBoard connectorStatus() {
        return this;
    }
}
