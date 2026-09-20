package dev.duo.harness.sessionquery;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.tools.ToolsService;

import java.nio.file.Path;
import java.util.Set;

/**
 * 会话检索插件（M21，ADR-0022 决策 8）：yml 行 opt-in——行在场即发布
 * "session-query" 服务（Web 搜索框数据源），tools 服务在册时同时注册
 * session_search 工具（模型侧）。不装零感知：索引懒构建，启动零成本。
 *
 * <p>会话目录缺省 DuoHome 体系的 {@code agent-sessions}（presenters 同源）；
 * config 可覆写：</p>
 * <pre>{@code config:
 *   sessionsDir: /path/to/sessions # 会话目录（省略用 duo home 缺省；测试注入用）
 *   maxResults: 8                  # 单次搜索命中上限（省略默认 8，须为正）}</pre>
 */
public final class SessionQueryPlugin implements Plugin<JsonNode> {

    /** 缺省命中上限（与 web_search 结果量级对齐）。 */
    static final int DEFAULT_MAX_RESULTS = 8;

    @Override
    public Set<String> inject() {
        return Set.of();
    }

    /** tools 为可选依赖：纯呈现位部署（无工具域）只发检索服务，不注册工具。 */
    @Override
    public Set<String> optionalInject() {
        return Set.of(ToolsService.SERVICE_NAME);
    }

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        Path sessionsDir = parseSessionsDir(config);
        int maxResults = parseMaxResults(config);
        InvertedSessionIndex index = new InvertedSessionIndex(sessionsDir);
        Disposable published = ctx.provide(SessionQueryService.SERVICE_NAME, index);
        if (ctx.hasService(ToolsService.SERVICE_NAME)) {
            ctx.as(SessionQueryToolsView.class).tools()
                    .register(ctx, new SessionSearchTool(index, maxResults));
        }
        return () -> {
            try {
                published.dispose();
            } catch (Exception ignored) {
                // 服务回收失败无可补救：树正在拆，注册表随 tree 释放
            }
        };
    }

    /** 会话目录解析：config.sessionsDir 覆写优先，缺省 DuoHome agent-sessions。 */
    private static Path parseSessionsDir(JsonNode config) {
        if (config != null && config.hasNonNull("sessionsDir")) {
            String value = config.get("sessionsDir").asText().strip();
            if (value.isEmpty()) {
                throw new PluginException("sessionsDir 不能为空串");
            }
            return Path.of(value);
        }
        return DuoHome.resolve().resolveDir("agent-sessions");
    }

    /**
     * 命中上限解析（{@code config.maxResults}）：缺席默认 8；在场必须正整数——
     * 非整数/非正一律异常点名（parsePageSize 同纪律，配置错误不静默纠正）。
     */
    static int parseMaxResults(JsonNode config) {
        if (config == null || !config.hasNonNull("maxResults")) {
            return DEFAULT_MAX_RESULTS;
        }
        JsonNode value = config.get("maxResults");
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new PluginException("maxResults 必须是整数: " + value);
        }
        int parsed = value.asInt();
        if (parsed < 1) {
            throw new PluginException("maxResults 必须为正: " + parsed);
        }
        return parsed;
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface SessionQueryToolsView {

        ToolsService tools();
    }
}
