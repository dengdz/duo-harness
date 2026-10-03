package dev.duo.harness.core.internal;

import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.RowSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 装载行登记簿：行 id → 运行期句柄，挂根作用域（树存活期存活）。
 *
 * <p>LinkedHashMap 保装载序（与 {@link PluginRegistry} 快照的挂载序口径一致），
 * 外层同步（synchronizedMap）覆盖行管理操作的串行化——装载/拔除是操作者驱动
 * 的低频动作，整段持锁（含 apply 的同步装载）换来"查重-登记"原子性，不引入
 * 新状态机（ADR-0037：只开门、不加机制）。</p>
 */
final class RowRegistry {

    private final Map<String, RowEntry> rows = Collections.synchronizedMap(new LinkedHashMap<>());

    /** 行条目：装载时点冻结的插件类名 + 活句柄（状态经句柄实时读）。 */
    record RowEntry(String pluginName, PluginHandle handle) {
    }

    /** 登记（id 重复抛点名异常——防御性：boot 路径 parseRows 已静态查重，运行期路径调用方先查）。 */
    synchronized void add(String id, String pluginName, PluginHandle handle) {
        if (rows.containsKey(id)) {
            throw new PluginException("行 id \"" + id + "\" 已装载（重复登记点名拒绝）");
        }
        rows.put(id, new RowEntry(pluginName, handle));
    }

    /** 按 id 取条目；未装载返回 null（调用方决定点名口径）。 */
    synchronized RowEntry get(String id) {
        return rows.get(id);
    }

    /** 按 id 取条目，未装载点名报错（get/dispose 的统一前置）。 */
    synchronized RowEntry require(String id) {
        RowEntry entry = rows.get(id);
        if (entry == null) {
            throw new PluginException("行 id \"" + id + "\" 未装载（运行期行级控制只及已登记行）");
        }
        return entry;
    }

    /** 摘除登记（拔除后同 id 可重装）。 */
    synchronized void remove(String id) {
        rows.remove(id);
    }

    /** 全部行快照（装载序；状态经句柄实时读）。 */
    synchronized List<RowSnapshot> snapshots() {
        List<RowSnapshot> result = new ArrayList<>(rows.size());
        for (Map.Entry<String, RowEntry> entry : rows.entrySet()) {
            result.add(new RowSnapshot(entry.getKey(), entry.getValue().pluginName(),
                    entry.getValue().handle().state()));
        }
        return result;
    }
}
