package dev.duo.harness.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.tools.ToolsService;

import java.util.Set;

/**
 * web 工具族插件（M20，ADR-0021）：yml 行声明 + config 块（可写空块 {}）即装配联网能力。
 *
 * <p>注册语义为<b>配置驱动注册</b>：行在场即注册 {@code web_fetch}（fetch 是零配置
 * 能力）；config {@code search} 段非空才注册 {@code web_search}——未配置搜索的部署
 * 呈 fetch-only 形态，模型工具清单干净收缩，永不撞必然失败的调用。</p>
 */
public final class WebToolsPlugin implements Plugin<JsonNode> {

    @Override
    public Set<String> inject() {
        return Set.of(ToolsService.SERVICE_NAME);
    }

    /** workspace 为可选依赖（ADR-0019）：fs 插件缺席的纯对话装配无档位语义，联网不设审批。 */
    @Override
    public Set<String> optionalInject() {
        return Set.of(dev.duo.harness.tools.fs.WorkspacePolicy.SERVICE_NAME);
    }

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        dev.duo.harness.tools.fs.WorkspacePolicy workspace =
                ctx.hasService(dev.duo.harness.tools.fs.WorkspacePolicy.SERVICE_NAME)
                        ? ctx.as(WebWorkspaceView.class).workspace() : null;
        return assemble(ctx.as(WebToolsView.class).tools(), ctx, config, System::getenv,
                workspace == null ? null
                        : () -> workspace.mode() == dev.duo.harness.tools.fs.WorkspacePolicy.Mode.READ_ONLY);
    }

    /**
     * 装配（包内可测）：fetch 恒注册；search 段非空且 key 链有解才注册 web_search
     * （ADR-0021 决策 2——无 key 降级 fetch-only 以工具面收缩兑现）。readOnlyGate
     * 供两工具做网络读档位声明（null = 无档位装配）。
     */
    static Disposable assemble(ToolsService tools, Context ctx, JsonNode config,
                               java.util.function.UnaryOperator<String> envLookup,
                               java.util.function.BooleanSupplier readOnlyGate) {
        WebToolsConfig cfg = WebToolsConfig.parse(config);
        tools.register(ctx, new WebFetchTool(cfg, UrlGuard.withSystemResolver(), readOnlyGate));
        if (cfg.search() != null) {
            String apiKey = cfg.search().resolveApiKey(envLookup);
            if (apiKey != null) {
                tools.register(ctx, new WebSearchTool(
                        new TavilyProvider(apiKey, cfg.search().effectiveBaseUrl(), cfg.timeoutMs(),
                                cfg.maxResponseBytes()),
                        cfg.search().effectiveMaxResults(), readOnlyGate));
            }
        }
        return () -> { };
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface WebToolsView {

        ToolsService tools();
    }

    /** workspace 服务的视图接口（方法名即服务名）。 */
    interface WebWorkspaceView {

        dev.duo.harness.tools.fs.WorkspacePolicy workspace();
    }
}
