package dev.duo.harness.core.internal;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.PluginRows;
import dev.duo.harness.core.api.RowSnapshot;

import java.util.List;
import java.util.Objects;

/**
 * {@link PluginRows} 的实现：根作用域行级控制门面。全部操作转发到根
 * {@link ContextImpl} 的既有机制（编程挂载 / 句柄销毁），自身零状态机。
 *
 * <p>public 供 api 包 {@link PluginRows#of} 全限定名委托（BootLoader 同款例外）。</p>
 */
public final class PluginRowsImpl implements PluginRows {

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
    public PluginHandle load(String id, Plugin<?> plugin, Object rawConfig) {
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
            rows.add(id, plugin.getClass().getName(), handle);
            return handle;
        }
    }

    @Override
    public void dispose(String id) {
        Objects.requireNonNull(id, "id");
        PluginHandle handle = rows.require(id).handle();
        // 句柄销毁幂等：服务注销传导依赖方回落 PENDING（六态机制），登记摘除后同 id 可重装
        handle.dispose();
        rows.remove(id);
    }
}
