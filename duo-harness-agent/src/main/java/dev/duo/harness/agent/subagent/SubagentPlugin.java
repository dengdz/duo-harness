package dev.duo.harness.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * subagent 插件（M15，ADR-0015）：模板制装配入口。config 定义一套或多套子代理
 * 模板（工具清单 + 可选专属提示）；模板非空时发布 {@code subagents} 服务
 * （{@link SubagentManager}）并注册五件工具；未配置模板的部署零副作用——不发布
 * 服务、不注册工具，对话行为与 0.9.0 完全一致。
 *
 * <p><b>依赖方向</b>：呈现位在装配时发布 {@code subagent-host} 运行期构件供给
 * （LLM / 治理阈值 / 当前会话），本插件经 inject 声明读取它构造子代理执行链——
 * 呈现位因此无须知道 subagent 是否存在（未配置模板部署零感知），而子代理本就必须
 * 有父 agent 运行环境（硬依赖语义成立）。</p>
 *
 * <p>模板在 config 解析期严格绑定（结构错误启动即点名，见
 * {@link SubagentTemplates#parse}）；子 agent 可用集在装配处再经强制过滤收窄
 * （{@link SubagentTemplate#allowedTools}，交互/控制面工具不可绕过）。</p>
 */
public final class SubagentPlugin implements Plugin<JsonNode> {

    @Override
    public Set<String> inject() {
        return Set.of(ToolsService.SERVICE_NAME, SubagentHost.SERVICE_NAME);
    }

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        SubagentTemplates templates = SubagentTemplates.parse(config);
        if (templates.isEmpty()) {
            return null; // 未配置模板：零注册、零副作用——升级零感知
        }
        SubagentHost host = ctx.as(HostView.class).presenter();
        SubagentManager manager = new SubagentManager(templates);
        manager.bindBackend(new EmbeddedSubagentBackend(host.llm(),
                ctx.as(ToolsView.class).tools(), host.tuning()));
        Disposable published = ctx.provide(SubagentManager.SERVICE_NAME, manager);
        ToolsService tools = ctx.as(ToolsView.class).tools();
        Disposable registered = registerAgentTools(ctx, tools, manager,
                host.currentSession(), AGENT_TOOLS);
        return () -> {
            registered.dispose();
            published.dispose();
        };
    }

    /**
     * 五件工具供给清单：spawn/fork 需当前父会话（呈现位经宿主供给），控制面三件
     * 只需管理者——全部由本插件注册（"仅装配了 subagent 模板的实例出现"由此处的
     * 模板非空条件单点把守，未配置部署零变化）。
     */
    static final List<BiFunction<SubagentManager, Supplier<Session>, ToolDefinition>> AGENT_TOOLS = List.of(
            (manager, session) -> new SpawnTool(manager, session),
            (manager, session) -> new ForkTool(manager, session),
            (manager, session) -> new SendMessageTool(manager),
            (manager, session) -> new InterruptAgentTool(manager),
            (manager, session) -> new ListAgentsTool(manager));

    /**
     * 条件注册机制：供给清单逐项实例化并注册进工具域。包内可见——测试以
     * fake 供给锁定"清单内全部注册、插件停止随作用域注销"的行为。
     */
    static Disposable registerAgentTools(Context ctx, ToolsService tools, SubagentManager manager,
                                         Supplier<Session> currentSession,
                                         List<BiFunction<SubagentManager, Supplier<Session>,
                                                 ToolDefinition>> suppliers) {
        List<Disposable> disposables = new ArrayList<>();
        for (BiFunction<SubagentManager, Supplier<Session>, ToolDefinition> supplier : suppliers) {
            disposables.add(tools.register(ctx, supplier.apply(manager, currentSession)));
        }
        return () -> {
            for (Disposable disposable : disposables) {
                disposable.dispose();
            }
        };
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface ToolsView {

        ToolsService tools();
    }

    /** presenter 服务的视图接口（方法名即服务名）。 */
    interface HostView {

        SubagentHost presenter();
    }
}
