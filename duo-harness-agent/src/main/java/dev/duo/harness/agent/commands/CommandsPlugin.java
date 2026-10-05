package dev.duo.harness.agent.commands;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;

/**
 * 命令注册表插件（M19，ADR-0020）：发布空命令注册表为 "commands" 服务——命令本体
 * 由各插件装配时注册（CLI 四命令、Web 入口同源）。零配置：发布即职责，摘除随
 * 作用域。不注册任何命令——保持与 PromptPlugin 同款的发布-only 形态。
 *
 * <p>另发布 "modelSwitch" 登记表（M38 工单 01，ADR-0040 决策三）：呈现位按发起面
 * 登记各自的模型/思考切换控制器——/model、/effort（ANY 双面）分发时按发起呈现位
 * 取用，实现「本呈现位独立」换链。</p>
 */
public final class CommandsPlugin implements Plugin<JsonNode> {

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        Disposable commands = ctx.provide(CommandsRegistry.SERVICE_NAME, new CommandsRegistry());
        Disposable modelSwitch = ctx.provide(ModelSwitchRegistry.SERVICE_NAME,
                new ModelSwitchRegistry() {
                    private final java.util.Map<CommandScope, ModelSwitchController> controllers =
                            new java.util.concurrent.ConcurrentHashMap<>();

                    @Override
                    public void register(CommandScope presenter, ModelSwitchController controller) {
                        if (presenter == null || controller == null) {
                            throw new IllegalArgumentException("modelSwitch 登记参数不可为 null（presenter/controller）");
                        }
                        controllers.put(presenter, controller);
                    }

                    @Override
                    public ModelSwitchController controller(CommandScope presenter) {
                        return controllers.get(presenter);
                    }
                });
        return () -> {
            commands.dispose();
            modelSwitch.dispose();
        };
    }
}
