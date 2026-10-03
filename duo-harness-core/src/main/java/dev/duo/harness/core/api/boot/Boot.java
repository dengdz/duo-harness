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

    /**
     * 从 classpath 资源引导插件树——缺省装配（jar 内资源）装载的正门：
     * 资源 URI 在 fat-jar 中非文件形态，经 {@link #from(Path)} 的文件化读取必炸
     * （M30 工单 01）。语义与 {@link #from(Path)} 完全一致（解析、审计点名、
     * 失败整树回滚）；错误点名以 {@code classpath:<resourcePath>} 标签标识来源。
     *
     * @param resourcePath 类路径绝对形态（带 {@code /} 前缀），经实现类（BootLoader）的
     *                     类加载器解析
     * @return 已激活的根 Context（审计通过，树存活）
     * @throws BootException 任何失败（阶段见 {@link BootException.Stage}）；
     *         失败时整树已回滚
     */
    public static Context fromResource(String resourcePath) {
        return fromResource(resourcePath, ctx -> {
        });
    }

    /** 同 {@link #from(Path, Consumer)} 的资源形态（回调时点一致）。 */
    public static Context fromResource(String resourcePath, Consumer<Context> onRootCreated) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        return dev.duo.harness.core.internal.boot.BootLoader.fromResource(resourcePath, onRootCreated);
    }

    /**
     * 确保用户装配文件在位并返回其路径（ADR-0037 工单 03 可写事实源）：
     * {@code DUO_HOME/plugins.yml} 存在即直接返回（种子仅作升级漂移对账参照）；
     * 缺失则把种子资源原子物化（临时文件 + 原子改名，不半写）后返回。缺省装配
     * 的用户态正门——预检与装载都应读这份有效文件（编辑物化文件即改装配），
     * 运行期行级操作的持久化也落回它（插件中心，M35 后续工单）。升级漂移
     * （内置种子随版本演进）只记日志提示对账，不阻断启动。
     *
     * @param seedResource 内置种子资源（classpath 绝对形态，带 {@code /} 前缀）
     * @return 有效装配文件路径（存在保证）
     */
    public static Path ensureUserAssembly(String seedResource) {
        return dev.duo.harness.core.internal.boot.BootLoader.ensureUserAssembly(seedResource);
    }
}
