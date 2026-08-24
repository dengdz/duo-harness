package dev.duo.harness.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsService;

import java.util.Set;

/**
 * 工具插件示例：inject "tools"，经服务注册 echo 工具
 * （注册即本插件作用域副作用，插件停止自动注销）。
 */
public final class EchoToolPlugin implements Plugin<Void> {

    @Override
    public Set<String> inject() {
        return Set.of(ToolsService.SERVICE_NAME);
    }

    @Override
    public Class<Void> configType() {
        return null;
    }

    @Override
    public Disposable apply(Context ctx, Void config) {
        ToolsService tools = ctx.as(ToolsView.class).tools();
        return tools.register(ctx, new ToolDefinition() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "回声工具：原样返回 input 参数";
            }

            /** 与实现诚实一致的参数 schema：模型可见面（M1 不校验，仅声明）。 */
            @Override
            public JsonNode parameters() {
                var factory = JsonNodeFactory.instance;
                var schema = factory.objectNode();
                schema.put("type", "object");
                schema.set("properties", factory.objectNode()
                        .set("input", factory.objectNode().put("type", "string")));
                schema.set("required", factory.arrayNode().add("input"));
                return schema;
            }

            @Override
            public Object execute(ToolExecution execution) {
                return "echo:" + execution.args().path("input").asText("");
            }
        });
    }
}
