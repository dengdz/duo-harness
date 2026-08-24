package dev.duo.harness.example;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;

/**
 * 服务提供者示例：发布 "greeting" 服务，前缀来自 config record
 * （演示 yml 配置绑定）。无依赖。
 */
public final class GreetingPlugin implements Plugin<GreetingPlugin.Config> {

    /** config record：yml 的 config 段绑定到这里（严格模式全字段必填）。 */
    public record Config(String prefix) {
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public Disposable apply(Context ctx, Config config) {
        ctx.provide("greeting", (GreetingService) name -> config.prefix() + ", " + name + "!");
        return null;
    }
}
