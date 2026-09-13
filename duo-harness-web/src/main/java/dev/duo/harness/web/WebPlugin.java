package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.ToolsService;

import java.util.Set;

/**
 * Web 双面插件（M8）：Boot yml 一行启用本地 Web 服务——静态单页（对话/状态双区）、
 * `/api/status` 状态 JSON、`/api/events` SSE 会话事件流。只绑 127.0.0.1，无鉴权
 * （本地个人工具场景，鉴权 M9+）。
 *
 * <p>装配自己的会话（续接/新建 `~/.duo/agent-sessions` 最新会话）与 tools 服务
 * （inject 声明）；对话面（agent 编排）由后续工单在同一服务上扩展。</p>
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
        return Set.of(ToolsService.SERVICE_NAME);
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
        Session session = Session.latest(DuoHome.resolve().resolveDir("agent-sessions"));
        if (session == null) {
            session = Session.create(DuoHome.resolve().resolveDir("agent-sessions"));
        }
        try {
            face = WebFace.start(port, ctx, tools, session);
        } catch (java.io.IOException e) {
            throw new dev.duo.harness.core.api.PluginException("Web 服务启动失败（端口 " + port + "）", e);
        }
        System.out.println("Web 面已启动: http://127.0.0.1:" + face.port());
        return face::stop;
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface WebToolsView {

        ToolsService tools();
    }
}
