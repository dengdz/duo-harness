package dev.duo.harness.center;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;

/**
 * JsonNode config 型夹具（回归锁用，M35 工单 06 实测口径）：声明 JsonNode
 * config 类型的插件，空对象 {} 是合法配置——归一化不得把它误判为"未提供配置"。
 */
public class JsonNodeConfigPlugin implements Plugin<JsonNode> {

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        ctx.provide("jsonNodeMarker", config == null ? "null" : config.toString());
        return () -> {
        };
    }
}
