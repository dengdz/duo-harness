package dev.duo.harness.core.api;

import java.util.List;

/**
 * 运行期行级控制（ADR-0037 内核受控口一）：按装载行 id 查询、拔除与重装已装载
 * 插件实例。六态状态机与依赖指纹语义原样复用——本接口只开门、不加机制：
 * {@link #dispose} 即 {@link PluginHandle#dispose()}（服务注销、依赖方自动回落
 * PENDING），{@link #load} 即根作用域编程挂载（服务回归、依赖方自动重载）。
 *
 * <p>获取：根作用域经 {@link #of(Context)}；树内插件经服务 {@code pluginRows}
 * （boot 成功后自动发布，视图接口方法名与服务名逐字一致；编程挂载的
 * {@link Context#root()} 树不自动发布，径用 {@code of}）。</p>
 *
 * <p>运行期操作不回写 yml——配置文件仍是重启后装配的唯一事实源；操作的
 * 持久化（装/停/卸落盘）属插件中心的编排职责（ADR-0037 决策二）。</p>
 *
 * <p>拔除语义：{@link #dispose} 后 id 登记同步摘除，同 id 可立即重装；拔除
 * 即"插件实例不存在"，与其提供的服务一并消失，重启后是否复活由 yml 决定。</p>
 */
public interface PluginRows {

    /** 服务名（boot 成功后自动发布；消费方视图接口方法名与此逐字一致）。 */
    String SERVICE_NAME = "pluginRows";

    /** 取根作用域的行级控制口；非根作用域点名拒绝（行级控制是装配层的口，不下放插件作用域）。 */
    static PluginRows of(Context root) {
        return dev.duo.harness.core.internal.PluginRowsImpl.of(root);
    }

    /** 全部已装载行（装载序；状态为实时快照，无缓存）。 */
    List<RowSnapshot> rows();

    /** 按 id 取运行期句柄（awaitStartup / state 用）；未装载点名报错。 */
    PluginHandle get(String id);

    /**
     * 装载新行：插件挂根作用域（树存活期存活，不随调用方作用域销毁），id 登记
     * 可寻。id 重复点名报错（拒绝重复装载）；装载失败（绑定或 apply 抛错）时
     * 同步抛出且不留登记残留。
     */
    default PluginHandle load(String id, Plugin<?> plugin, Object rawConfig) {
        return load(id, plugin, rawConfig, null);
    }

    /**
     * 同 {@link #load(String, Plugin, Object)}，另附随行关闭器：{@link #dispose}
     * 拔除该行时执行（插件包行的类加载器释放通道，ADR-0037 决策二）；关闭失败
     * 只记 warn——类加载器泄漏的兜底口径是"需重启生效"，不阻断拔除。
     */
    PluginHandle load(String id, Plugin<?> plugin, Object rawConfig, AutoCloseable closer);

    /**
     * 按 id 拔除：实例销毁（副作用逆序回滚、服务注销、依赖方自动回落等待），
     * 登记摘除（同 id 可重装）。幂等。未装载点名报错。
     */
    void dispose(String id);
}
