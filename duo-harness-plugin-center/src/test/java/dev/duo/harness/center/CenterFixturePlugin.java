package dev.duo.harness.center;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 插件包装载夹具（打进测试 fixture jar 的类）：从自身 jar 读 marker 资源并发布
 * 为具名服务——安装后服务值 = 包内 marker，即"装载的是这个包"的直接证据。
 */
public class CenterFixturePlugin implements Plugin<CenterFixturePlugin.FixtureConfig> {

    /** 装载配置：发布的服务名 + 自身包内 marker 资源名。 */
    public record FixtureConfig(String serviceName, String markerFile) {
    }

    @Override
    public Class<FixtureConfig> configType() {
        return FixtureConfig.class;
    }

    @Override
    public Disposable apply(Context ctx, FixtureConfig config) throws Exception {
        String marker;
        try (InputStream in = getClass().getResourceAsStream("/" + config.markerFile())) {
            marker = in == null ? "<marker 缺失>" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ctx.provide(config.serviceName(), marker);
        return () -> {
        };
    }
}
