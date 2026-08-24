package dev.duo.harness.example;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;

/**
 * disabled 行专用示例：无任何行为——boot 时行在场而实例不加载，
 * 用于演示 disabled 语义（保留行、不装载）。
 */
public final class DemoDisabledPlugin implements Plugin<Void> {

    @Override
    public Class<Void> configType() {
        return null;
    }

    @Override
    public Disposable apply(Context ctx, Void config) {
        return null;
    }
}
