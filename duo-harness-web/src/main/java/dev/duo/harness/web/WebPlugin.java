package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.PromptRegistry;
import dev.duo.harness.agent.PromptsView;
import dev.duo.harness.agent.internal.ToolCallingAgent;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.llm.RetryingAdapter;
import dev.duo.harness.llm.internal.OpenAiCompatAdapter;
import dev.duo.harness.tools.AnswersView;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.ToolsService;

import java.nio.file.Path;
import java.util.Set;

/**
 * Web 双面插件（M8）：Boot yml 一行启用本地 Web 服务——静态单页（对话/状态双区）、
 * `/api/status` 状态 JSON、`/api/events` SSE 会话事件流、`/api/message` 对话入口
 * （装配自己的对话执行者，全套 M5-M7 装配：重试适配器 + prompt 注册表 + 会话）。
 * 只绑 127.0.0.1，无鉴权（本地个人工具场景，鉴权 M9+）。
 *
 * <p>inject tools + prompts：状态面与对话面的两个数据源（标准服务注入模式）。
 * 技能清单 / AGENTS.md 片段由对应插件（SkillsPlugin / AgentsMdPlugin）注册进
 * prompts 服务——本插件只做对话执行者装配，不重复注册。LLM 未配置时插件
 * FAILED 点名（对话面不可用，状态面仍可看）。</p>
 *
 * <p>配置（块内字段可省）：</p>
 * <pre>{@code config:
 *   port: 8080   # 监听端口（省略默认 8080；只绑 127.0.0.1）}</pre>
 */
public final class WebPlugin implements Plugin<JsonNode> {

    /** 默认监听端口。 */
    public static final int DEFAULT_PORT = 8080;

    private WebFace face;

    @Override
    public Set<String> inject() {
        return Set.of(ToolsService.SERVICE_NAME, PromptRegistry.SERVICE_NAME, InteractionService.SERVICE_NAME);
    }

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        int port = config != null && config.hasNonNull("port")
                ? config.get("port").asInt(DEFAULT_PORT) : DEFAULT_PORT;
        ToolsService tools = ctx.as(WebToolsView.class).tools();
        PromptRegistry prompts = ctx.as(WebPromptsView.class).prompts();
        InteractionService answers = ctx.as(WebAnswersView.class).answers();

        // 对话执行者（M5 循环 + M6 prompt 注册表 + M8 重试；LLM 未配置 → 插件 FAILED 点名）
        LlmConfig llm = LlmConfig.load();
        var adapter = new RetryingAdapter(new OpenAiCompatAdapter(llm));

        Session session = Session.latest(DuoHome.resolve().resolveDir("agent-sessions"));
        if (session == null) {
            session = Session.create(DuoHome.resolve().resolveDir("agent-sessions"));
        }
        ChatAgent agent = new ToolCallingAgent(adapter, tools, session, prompts);
        // HITL Web answerer：注册进交互 seam（断连 fail-closed 由 WebFace 联动）
        WebAnswerer webAnswerer = new WebAnswerer(10 * 60 * 1000L);
        answers.register(ctx, webAnswerer);

        try {
            face = WebFace.start(port, ctx, tools, session, agent, webAnswerer);
        } catch (java.io.IOException e) {
            throw new PluginException("Web 服务启动失败（端口 " + port + "）", e);
        }
        // /new：全新会话 + 重建 agent（重试装饰器复用）
        face.onNewSession(
                () -> Session.create(DuoHome.resolve().resolveDir("agent-sessions")),
                fresh -> face.setAgent(new ToolCallingAgent(adapter, tools, fresh, prompts)));
        System.out.println("Web 面已启动: http://127.0.0.1:" + face.port());
        return face::stop;
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface WebToolsView {

        ToolsService tools();
    }

    /** prompts 服务的视图接口（方法名即服务名）。 */
    interface WebPromptsView {

        PromptRegistry prompts();
    }

    /** 交互服务的视图接口（方法名即服务名 "answers"）。 */
    interface WebAnswersView {

        InteractionService answers();
    }
}
