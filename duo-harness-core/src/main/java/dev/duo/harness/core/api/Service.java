package dev.duo.harness.core.api;

/**
 * 服务的便利基类：构造即发布、所在插件停止即注销。
 *
 * <p>不继承本类亦可直接经 {@code ctx.provide(name, instance)} 发布任意实例；
 * 本基类只为"一个类一个服务"的常见形态省去样板。</p>
 */
public abstract class Service {

    private final Context owner;
    private final String name;

    /**
     * 子类构造器调用本构造器时，服务即以 {@code name} 发布到当前作用域。
     *
     * <p><b>构造器逃逸约束</b>：发布会同步唤醒依赖本服务的挂起插件并立即
     * 调用服务方法——此刻子类构造器体尚未执行。因此子类的全部状态必须在
     * 字段声明处初始化（final 或就地赋值），不得依赖构造器体。</p>
     *
     * @param owner 插件的 Context（服务随该插件实例生命周期注销）
     * @param name 服务名；harness 保留裸名（tools、llm…），第三方服务加前缀
     */
    protected Service(Context owner, String name) {
        this.owner = owner;
        this.name = name;
        owner.provide(name, this);
    }

    /** 服务名（发布时的身份）。 */
    public final String serviceName() {
        return name;
    }

    /** 发布本服务的插件 Context。 */
    protected final Context ownerContext() {
        return owner;
    }
}
