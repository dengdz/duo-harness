package dev.duo.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.tools.ToolsService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 技能插件（M7）：启动扫描四根发现（默认根，可配禁用表），聚合清单片段注册进
 * prompt 注册表、`skill` 工具注册进工具域，并发布技能注册表为 "skills" 服务
 * （用户直调与计划模式之外的消费者经视图寻址）。
 *
 * <p>配置（块内字段可省）：</p>
 * <pre>{@code config:
 *   disabled: ["某技能名"]   # 禁用的技能名列表（省略即全启用）}</pre>
 *
 * <p>inject tools + prompts：技能的清单片段与工具分别是两个域的消费者——
 * 标准服务注入模式（InteractiveApprovalPlugin 同款）。</p>
 */
public final class SkillsPlugin implements Plugin<JsonNode> {

    @Override
    public Set<String> inject() {
        return Set.of(ToolsService.SERVICE_NAME, PromptRegistry.SERVICE_NAME);
    }

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        Set<String> disabled = parseDisabled(config);
        SkillRegistry registry = SkillRegistry.scan(SkillRegistry.defaultRoots(), disabled);

        PromptRegistry prompts = ctx.as(PromptsView.class).prompts();
        ToolsService tools = ctx.as(ToolsView.class).tools();

        Disposable published = ctx.provide(SkillRegistry.SERVICE_NAME, registry);
        String catalog = registry.catalogFragment();
        Disposable fragment = catalog == null ? null : prompts.register(ctx,
                new PromptFragment("skills:catalog", catalog));
        Disposable tool = tools.register(ctx, new SkillTool(registry));
        return () -> {
            if (fragment != null) {
                fragment.dispose();
            }
            tool.dispose();
            published.dispose();
        };
    }

    /** 禁用表解析：字符串数组；缺省或非法即空集。 */
    private static Set<String> parseDisabled(JsonNode config) {
        JsonNode node = config == null ? null : config.get("disabled");
        if (node == null || !node.isArray()) {
            return Set.of();
        }
        Set<String> disabled = new LinkedHashSet<>();
        node.forEach(n -> disabled.add(n.asText()));
        return Set.copyOf(disabled);
    }

    /** prompts 服务的视图接口（方法名即服务名）。 */
    interface PromptsView {

        PromptRegistry prompts();
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface ToolsView {

        ToolsService tools();
    }
}
