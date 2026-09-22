package dev.duo.harness.agent.skills;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.agent.prompt.PromptFragment;
import dev.duo.harness.agent.prompt.PromptRegistry;
import dev.duo.harness.tools.ToolsService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.nio.file.Path;
import java.util.Set;

/**
 * 技能插件（M7；M23 起热加载）：启动扫描四根发现（默认根，可配禁用表），聚合
 * 清单片段注册进 prompt 注册表、`skill` 工具注册进工具域，并发布技能注册表为
 * "skills" 服务（用户直调与计划模式之外的消费者经视图寻址）；M23 起接线技能
 * watch——技能变更时清单片段按 digest 去重重发（SkillRegistry.startWatch）。
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
        List<Path> roots = SkillRegistry.defaultRoots();
        SkillRegistry registry = SkillRegistry.scan(roots, disabled);

        PromptRegistry prompts = ctx.as(PromptsView.class).prompts();
        ToolsService tools = ctx.as(ToolsView.class).tools();

        Disposable published = ctx.provide(SkillRegistry.SERVICE_NAME, registry);
        // 清单片段引用（热加载重发用：digest 未变化时回调不触发，保证
        // "变化才重发、只重发一次"；句柄唯一出口为 fragmentRef）
        java.util.concurrent.atomic.AtomicReference<Disposable> fragmentRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        registerCatalog(prompts, ctx, registry, fragmentRef);
        Disposable tool = tools.register(ctx, new SkillTool(registry));
        // 技能热加载（M23 工单 09，ADR-0025）：watch 四发现根（含根目录创建），
        // 变更 → 重扫描 + 片段按 digest 去重重发；watch 不可用降级启动扫描
        Disposable watch = registry.startWatch(roots, disabled, () -> {
            try {
                Disposable stale = fragmentRef.get();
                Disposable fresh = registerCatalog(prompts, ctx, registry, fragmentRef);
                // 先注册新片段成功再摘旧（CopyOnWrite 快照下瞬时双份无害）——
                // 刷新窗口期清单不缺失，注册失败也不丢上一版片段；
                // fresh == null（技能全删清空清单）同样摘旧，防残留陈旧清单
                if (stale != null && stale != fresh) {
                    stale.dispose();
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(SkillsPlugin.class)
                        .warn("技能清单片段热刷新失败（{}）——保持上一版片段", e.toString());
            }
        });
        return () -> {
            watch.dispose();
            Disposable current = fragmentRef.get();
            if (current != null) {
                current.dispose();
            }
            tool.dispose();
            published.dispose();
        };
    }

    /**
     * 注册清单片段（非空才注册；空清单时清空引用——调用方据此摘除旧片段，
     * 防技能全删后残留陈旧清单），注册句柄记入引用（刷新/停表用）。
     */
    private static Disposable registerCatalog(PromptRegistry prompts, Context ctx,
                                              SkillRegistry registry,
                                              java.util.concurrent.atomic.AtomicReference<Disposable> fragmentRef) {
        String catalog = registry.catalogFragment();
        if (catalog == null) {
            fragmentRef.set(null);
            return null;
        }
        Disposable fragment = prompts.register(ctx, new PromptFragment("skills:catalog", catalog));
        fragmentRef.set(fragment);
        return fragment;
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
