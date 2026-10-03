package dev.duo.harness.core.internal;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.PluginRows;
import dev.duo.harness.core.api.RowSnapshot;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PluginRows} 的实现：根作用域行级控制门面。全部操作转发到根
 * {@link ContextImpl} 的既有机制（编程挂载 / 句柄销毁），自身零状态机。
 *
 * <p>public 供 api 包 {@link PluginRows#of} 全限定名委托（BootLoader 同款例外）。</p>
 */
public final class PluginRowsImpl implements PluginRows {

    private static final Logger log = LoggerFactory.getLogger(PluginRowsImpl.class);

    /** 根作用域（装载挂载点；行级控制只及根——装配层的口不下放插件作用域）。 */
    private final ContextImpl root;
    private final RowRegistry rows;

    private PluginRowsImpl(ContextImpl root) {
        this.root = root;
        this.rows = root.rows();
    }

    /** api 薄壳委托入口：校验根作用域身份后开门。 */
    public static PluginRows of(Context context) {
        Objects.requireNonNull(context, "context");
        if (!(context instanceof ContextImpl impl) || !impl.isRoot()) {
            throw new PluginException("行级控制仅根作用域可用（PluginRows.of 收到非根作用域）");
        }
        return new PluginRowsImpl(impl);
    }

    @Override
    public List<RowSnapshot> rows() {
        return rows.snapshots();
    }

    @Override
    public PluginHandle get(String id) {
        Objects.requireNonNull(id, "id");
        return rows.require(id).handle();
    }

    @Override
    public PluginHandle load(String id, Plugin<?> plugin, Object rawConfig, AutoCloseable closer) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new PluginException("行 id 不能为空");
        }
        Objects.requireNonNull(plugin, "plugin");
        synchronized (rows) {
            if (rows.get(id) != null) {
                throw new PluginException("行 id \"" + id + "\" 已装载（重复装载点名拒绝，"
                        + "先拔除后方可重装）");
            }
            // 挂根作用域：树存活期存活，不随调用方作用域销毁；apply 同步执行
            // （ADR-0002），失败异常原样上抛且未登记——无残留
            PluginHandle handle = root.plugin(plugin, rawConfig);
            rows.add(id, plugin.getClass().getName(), handle, closer);
            return handle;
        }
    }

    @Override
    public void dispose(String id) {
        Objects.requireNonNull(id, "id");
        RowRegistry.RowEntry entry = rows.require(id);
        // 句柄销毁幂等：服务注销传导依赖方回落 PENDING（六态机制），登记摘除后同 id 可重装
        entry.handle().dispose();
        closeQuietly(id, entry.closer());
        rows.remove(id);
    }

    /** 随行关闭器释放（插件包类加载器）；失败只记 warn——泄漏兜底口径"需重启生效"，不阻断拔除。 */
    private static void closeQuietly(String id, AutoCloseable closer) {
        if (closer == null) {
            return;
        }
        try {
            closer.close();
        } catch (Exception e) {
            log.warn("行 {} 的随行关闭器执行失败（类加载器泄漏时需重启生效兜底）", id, e);
        }
    }
}
