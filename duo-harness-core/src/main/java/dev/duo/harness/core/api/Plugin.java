package dev.duo.harness.core.api;

/**
 * 插件：声明依赖与 apply 入口的扩展单元。插件是描述，加载后才有生命
 * （运行期实体见 {@link PluginHandle}）。
 *
 * <p>同一插件实现可多次加载，各得一个实例、各一份配置。</p>
 *
 * @param <C> config record 类型；无配置插件用 {@link Void} 且 rawConfig 传 {@code null}
 */
public interface Plugin<C> {

    /**
     * 声明 config 类型，内核据此把原始配置（Map / JsonNode）绑定到强类型 record。
     * 返回 {@code null} 表示该插件不接受配置。
     */
    Class<C> configType();

    /**
     * 插件本体：在此注册服务、监听器、子插件等一切副作用（经 {@code ctx.effect}
     * 或 {@code ctx.plugin}），它们的回收由内核保证。
     *
     * <p>返回值作为最后一个副作用：作用域销毁时最先于显式 effect 之前注册的顺序参与逆序回滚。
     * 返回 {@code null} 表示没有整体性清理工作。</p>
     *
     * @throws Exception 任何异常都只导致本插件实例失败并被清理，不传染兄弟插件
     */
    Disposable apply(Context ctx, C config) throws Exception;
}
