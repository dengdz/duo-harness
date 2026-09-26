package dev.duo.harness.agent.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.boot.DuoHome;

import java.nio.file.Path;
import java.util.Set;

/**
 * AGENTS.md 链插件（M7 最小链 → M25 工单 07 meta_user 通道）：发布 {@link AgentsMdChain}
 * 为 "agentsMd" 服务——链内容以 user 角色置于请求消息序列最前（meta_user 通道，
 * 请求视图专用不落会话日志），链语义（用户全局 → 项目根 → 嵌套子目录链）与
 * 每请求现发现现读见 {@link AgentsMd}。消费方为呈现位装配（CLI/Web/headless，
 * optionalInject 接线）；子代理不注入（M7#4 口径不变）。
 *
 * <p>配置（块内字段可省；非法值（≤0/非数值）静默回退缺省 64KB）：</p>
 * <pre>{@code config:
 *   budgetChars: 65536   # 总预算字符数（省略即 64KB）}</pre>
 */
public final class AgentsMdPlugin implements Plugin<JsonNode> {

    @Override
    public Set<String> inject() {
        return Set.of();
    }

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    private static final String BUDGET_CHARS_CONFIG = "budgetChars";

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        JsonNode budgetNode = config == null ? null : config.get(BUDGET_CHARS_CONFIG);
        long budget = budgetNode != null && budgetNode.canConvertToLong() && budgetNode.asLong() > 0
                ? budgetNode.asLong() : AgentsMd.DEFAULT_BUDGET_CHARS;
        AgentsMdChain chain = new AgentsMdChain(
                Path.of(System.getProperty("user.dir")),
                DuoHome.resolve().root().resolve(AgentsMd.FILE_NAME), budget);
        return ctx.provide(AgentsMdChain.SERVICE_NAME, chain);
    }
}
