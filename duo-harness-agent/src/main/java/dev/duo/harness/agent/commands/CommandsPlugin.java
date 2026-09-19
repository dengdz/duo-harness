package dev.duo.harness.agent.commands;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;

/**
 * 命令注册表插件（M19，ADR-0020）：发布空命令注册表为 "commands" 服务——命令本体
 * 由各插件装配时注册（CLI 四命令、Web 入口同源）。零配置：发布即职责，摘除随
 * 作用域。不注册任何命令——保持与 PromptPlugin 同款的发布-only 形态。
 */
public final class CommandsPlugin implements Plugin<JsonNode> {

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        return ctx.provide(CommandsRegistry.SERVICE_NAME, new CommandsRegistry());
    }
}
