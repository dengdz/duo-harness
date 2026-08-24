package dev.duo.harness.example;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;

import java.util.Set;

/**
 * 服务消费者示例：inject 声明依赖 greeting，经视图接口调用服务，
 * 结果发 demo/log 事件（演示依赖等待与视图寻址）。
 */
public final class GreetingClientPlugin implements Plugin<Void> {

    @Override
    public Set<String> inject() {
        return Set.of(GreetingService.SERVICE_NAME);
    }

    @Override
    public Class<Void> configType() {
        return null;
    }

    @Override
    public Disposable apply(Context ctx, Void config) {
        GreetingService greeting = ctx.as(GreetingView.class).greeting();
        ctx.emit(DemoMain.DEMO_LOG_CHANNEL, "消费者经视图获得: " + greeting.greet("世界"));
        return null;
    }
}
