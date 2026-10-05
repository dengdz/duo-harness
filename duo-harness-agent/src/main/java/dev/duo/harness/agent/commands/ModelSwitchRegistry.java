package dev.duo.harness.agent.commands;

/**
 * 模型/思考切换状态登记表（M38 工单 01，ADR-0040 决策三）：按呈现位登记各执行链
 * 的切换控制器——/model、/effort 命令（ANY 双面）在分发时按发起呈现位取用，实现
 * 「本呈现位独立」：Web 切只影响 Web 链，CLI 切只影响 CLI 链。
 *
 * <p>登记方 = 各呈现位插件装配期（WebPlugin 登记 WEB、CliPlugin 登记 CLI）；重复
 * 登记覆盖（行拔除重装后新控制器顶替旧实例）。命令名注册先到先得（web 行先于
 * cli 行，命令由 WebPlugin 先注册、CliPlugin 查重跳过）——单一命令注册经本登记表
 * 服务双呈现位，登记表本体随 commands 服务供给方存活。</p>
 */
public interface ModelSwitchRegistry {

    /** 服务名（camelCase，视图接口方法名逐字一致）。 */
    String SERVICE_NAME = "modelSwitch";

    /** 登记呈现位的切换控制器（同呈现位重复登记 = 覆盖）。 */
    void register(CommandScope presenter, ModelSwitchController controller);

    /** 取呈现位的控制器；未登记返回 null（调用方给「不支持切换」文案）。 */
    ModelSwitchController controller(CommandScope presenter);
}
