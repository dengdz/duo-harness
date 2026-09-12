package dev.duo.harness.example.mcpfs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.events.WaterfallListener;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 重复调用提醒治理插件（M6 可靠性小件）：同一工具以相同参数连续重复调用达到
 * 阈值时，在工具结果尾部附加逐级加码的提醒文本（advisory——不改错误形态、
 * 不否决执行）。模型看到提醒后自行换方法；硬性兜底仍是迭代上限。
 *
 * <p>提醒经 post-execute 结果改写施加（工具本体零改动）；**不是 guard**——
 * guard 是单调否决，语义不兼容 advisory 提醒。非线程安全：按会话内串行
 * 执行计数（ToolCallingAgent 单会话串行约定）。</p>
 *
 * <p>配置（可选，省略即默认阈值 3/5/8）：</p>
 * <pre>{@code config: {"thresholds": [3, 5, 8]}}</pre>
 */
public final class RepeatReminderPlugin implements Plugin<JsonNode> {

    /** 默认提醒阈值（连续重复次数，逐级加码）。 */
    static final List<Integer> DEFAULT_THRESHOLDS = List.of(3, 5, 8);

    /** 连续重复检测状态（同 key 连续计数；单会话串行，不跨线程）。 */
    private String lastKey = "";
    private int consecutive;

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        List<Integer> thresholds = parseThresholds(config);
        Disposable listener = ctx.on(ToolsService.POST_EXECUTE, reminderFor(thresholds));
        return listener;
    }

    /** 提醒监听器：around 语义先放行内层，再对非错误结果附加提醒。 */
    private WaterfallListener<ToolExecution, Boolean> reminderFor(List<Integer> thresholds) {
        return (exec, next) -> {
            Boolean inner = next.invoke(exec);
            String key = exec.toolName() + "|" + exec.args();
            if (key.equals(lastKey)) {
                consecutive++;
            } else {
                lastKey = key;
                consecutive = 1;
            }
            if (!exec.resultIsError() && consecutive >= thresholds.get(0)) {
                exec.setResult(String.valueOf(exec.result()) + "\n\n" + reminderText(consecutive, thresholds));
            }
            return inner;
        };
    }

    /** 提醒文本：按跨越的阈值档位逐级加码。 */
    static String reminderText(int consecutive, List<Integer> thresholds) {
        int level = 0;
        for (int i = 0; i < thresholds.size(); i++) {
            if (consecutive >= thresholds.get(i)) {
                level = i + 1;
            }
        }
        return switch (level) {
            case 1 -> "[提醒] 你已连续 " + consecutive + " 次以相同参数调用同一工具，"
                    + "请考虑换方法、调整参数或向用户说明情况。";
            case 2 -> "[提醒] 重复调用已升级（第 " + consecutive + " 次）——当前方法大概率无效，"
                    + "请立即换一种思路。";
            default -> "[提醒] 重复调用已达警戒线（第 " + consecutive + " 次）！"
                    + "必须停止当前方法：要么改变方案，要么如实向用户说明障碍。";
        };
    }

    /** 阈值解析：升序正整数列表；缺省或非法回落默认 3/5/8。 */
    private static List<Integer> parseThresholds(JsonNode config) {
        JsonNode node = config == null ? null : config.get("thresholds");
        if (node == null || !node.isArray() || node.isEmpty()) {
            return DEFAULT_THRESHOLDS;
        }
        List<Integer> parsed = new ArrayList<>();
        node.forEach(n -> parsed.add(n.asInt()));
        int prev = 0;
        for (int v : parsed) {
            if (v <= prev) {
                return DEFAULT_THRESHOLDS;
            }
            prev = v;
        }
        return List.copyOf(parsed);
    }
}
