package dev.duo.harness.stats;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * 工具调用计数器：按工具名累计执行次数与失败次数。计数点在工具三段管线的
 * 结果治理段（{@code tools/post-execute}）——进入执行段的调用才计数，pre-execute
 * 否决与审批挂起不经过该段、不计入。
 *
 * <p>线程安全：工具并行池多管线并发写（ADR-0018），计数器用 LongAdder；
 * 读侧（报表/JSON）取即时快照，不与写互斥。</p>
 */
public final class ToolStats {

    private final ConcurrentMap<String, LongAdder> totals = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LongAdder> failures = new ConcurrentHashMap<>();

    /** 记录一次执行。 */
    public void record(String toolName, boolean failed) {
        totals.computeIfAbsent(toolName, k -> new LongAdder()).increment();
        if (failed) {
            failures.computeIfAbsent(toolName, k -> new LongAdder()).increment();
        }
    }

    /** 单工具执行总次数（无记录为 0）。 */
    public long total(String toolName) {
        LongAdder adder = totals.get(toolName);
        return adder == null ? 0 : adder.sum();
    }

    /** 单工具失败次数（无记录为 0）。 */
    public long failed(String toolName) {
        LongAdder adder = failures.get(toolName);
        return adder == null ? 0 : adder.sum();
    }

    /** 全部出现过的工具名，按总次数降序、同数按名字典序（次序稳定）。 */
    public List<String> toolsByVolume() {
        return totals.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, LongAdder>>comparingLong(
                                e -> e.getValue().sum()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /** 文本报表（/toolstats 命令输出）。 */
    public String toTable() {
        List<String> names = toolsByVolume();
        if (names.isEmpty()) {
            return "暂无工具执行记录（统计自插件挂载起）。";
        }
        StringBuilder out = new StringBuilder("工具使用统计（按总量降序）:\n");
        for (String name : names) {
            out.append("- ").append(name)
                    .append(": ").append(total(name)).append(" 次")
                    .append("（失败 ").append(failed(name)).append("）\n");
        }
        return out.toString().stripTrailing();
    }

    /** JSON 序列化器（ObjectMapper 构建较重，静态复用；线程安全）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** JSON 形态（tool_stats 查询工具返回，模型侧消费）。 */
    public String toJson() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        var usage = root.putArray("usage");
        for (String name : toolsByVolume()) {
            ObjectNode entry = usage.addObject();
            entry.put("tool", name);
            entry.put("total", total(name));
            entry.put("failed", failed(name));
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // 兜底保持工具结果契约（不回 null）；stderr 留痕防静默
            System.err.println("[tool-stats] 统计 JSON 序列化失败，回退空报表: " + e);
            return "{\"usage\":[]}";
        }
    }
}
