package dev.duo.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;

import java.nio.file.Path;
import java.util.Set;

/**
 * AGENTS.md 注入插件（M7）：启动时加载用户全局与项目根 AGENTS.md（64KB 预算，
 * 超限截断），注册为 `agents-md` 片段进 prompt 注册表——排在用户配置片段之后、
 * 其他插件片段之前（yml 行序：prompts → agents-md → 其余）。
 *
 * <p>配置（块内字段可省）：</p>
 * <pre>{@code config:
 *   budgetChars: 65536   # 总预算字符数（省略即 64KB）}</pre>
 *
 * <p>inject prompts：注入是 prompt 注册表的消费者（标准服务注入模式）；
 * prompts 缺位时本插件 PENDING 点名可见。两个候选文件都不存在时不注册片段
 * （无约定即无注入）。</p>
 */
public final class AgentsMdPlugin implements Plugin<JsonNode> {

    @Override
    public Set<String> inject() {
        return Set.of(PromptRegistry.SERVICE_NAME);
    }

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        long budget = config != null && config.hasNonNull("budgetChars")
                && config.get("budgetChars").asLong() > 0
                ? config.get("budgetChars").asLong() : AgentsMd.DEFAULT_BUDGET_CHARS;
        String text = AgentsMd.load(Path.of(System.getProperty("user.dir")), budget);
        if (text == null) {
            return () -> { };
        }
        PromptRegistry prompts = ctx.as(PromptsView.class).prompts();
        return prompts.register(ctx, new PromptFragment(AgentsMd.FRAGMENT_SOURCE, text));
    }
}
