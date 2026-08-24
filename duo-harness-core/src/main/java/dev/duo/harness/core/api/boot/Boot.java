package dev.duo.harness.core.api.boot;

import dev.duo.harness.core.api.Context;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 配置驱动 boot 的契约入口：读取单文件 YAML（plugins.yml 形态），逐行把
 * 插件类经编程 API 装载成插件树，收尾审计——失败行点名（含等待依赖的
 * 缺失服务清单），任何失败整树回滚后抛 {@link BootException}。
 *
 * <p>一次性引导：改配置后重启进程生效；运行时行级热重载属 HMR 范畴，
 * 不在本类职责内。实现（Jackson/YAML 解析、装载、审计）在
 * {@code internal.boot.BootLoader}——契约包不留第三方依赖。</p>
 *
 * <p>配置行结构（行字段即 spec 决策浓缩形状）：</p>
 * <pre>
 * plugins:
 *   - id: echo-tool          # 稳定标识，审计点名与层序合成的锚点，必填
 *     name: com.x.EchoPlugin  # 插件类 FQCN，须有公共无参构造
 *     config: { ... }         # 任意结构，绑定到插件的 config record
 *     disabled: false         # true = 跳过该行（保留行，不加载实例）
 * </pre>
 */
public final class Boot {

    private Boot() {
    }

    /**
     * 从配置文件引导插件树。
     *
     * @param configFile YAML 配置文件路径
     * @return 已激活的根 Context（审计通过，树存活）
     * @throws BootException 任何失败（阶段见 {@link BootException.Stage}）；
     *         失败时整树已回滚
     */
    public static Context from(Path configFile) {
        return from(configFile, ctx -> {
        });
    }

    /**
     * 从配置文件引导插件树，根 Context 创建后、任何行装载前回调
     * {@code onRootCreated}（对齐 DSH 的 prepare 语义）——用于提前挂
     * 全局监听器（如 plugin/status 状态叙述）。
     *
     * @throws BootException 同 {@link #from(Path)}
     */
    public static Context from(Path configFile, Consumer<Context> onRootCreated) {
        Objects.requireNonNull(onRootCreated, "onRootCreated");
        return dev.duo.harness.core.internal.boot.BootLoader.from(configFile, onRootCreated);
    }
}
