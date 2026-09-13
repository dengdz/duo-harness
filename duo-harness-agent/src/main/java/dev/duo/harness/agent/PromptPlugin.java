package dev.duo.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;

/**
 * prompt 注册表插件：发布 prompt 注册表为 "prompts" 服务（M6 定名，M7 起有
 * 装配级消费者——技能清单、AGENTS.md 片段都要注册进来）。
 *
 * <p>配置（块内字段可省）：</p>
 * <pre>{@code config:
 *   systemPrompt: "可选的用户指令（组装时排最前）"}</pre>
 */
public final class PromptPlugin implements Plugin<JsonNode> {

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        String userPrompt = config != null && config.hasNonNull("systemPrompt")
                ? config.get("systemPrompt").asText() : null;
        return ctx.provide(PromptRegistry.SERVICE_NAME, new PromptRegistry(userPrompt));
    }
}
