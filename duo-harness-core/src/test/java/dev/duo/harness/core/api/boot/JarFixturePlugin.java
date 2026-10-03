package dev.duo.harness.core.api.boot;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 插件包装载夹具（打进测试 fixture jar 的类）：从<b>自身 jar</b> 读 marker
 * 资源并发布为具名服务。隔离证据的关键设计——同一 class 字节打进两个 marker
 * 不同的 jar：自优先加载器各自读到本包资源（服务值不同），parent-first 则会
 * 统一落到宿主 classpath 副本（读不到 marker，服务值退化为占位串）。
 */
public class JarFixturePlugin implements Plugin<JarFixturePlugin.JarBoxConfig> {

    /** 装载配置：发布的服务名 + 自身包内 marker 资源名。 */
    public record JarBoxConfig(String serviceName, String markerFile) {
    }

    @Override
    public Class<JarBoxConfig> configType() {
        return JarBoxConfig.class;
    }

    @Override
    public Disposable apply(Context ctx, JarBoxConfig config) throws Exception {
        String marker;
        try (InputStream in = getClass().getResourceAsStream("/" + config.markerFile())) {
            marker = in == null ? "<marker 缺失>" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ctx.provide(config.serviceName(), marker);
        return () -> {
        };
    }
}
