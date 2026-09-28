package dev.duo.harness.web;

/**
 * Web 面的服务视图接口集合（M28 工单 06）：命令、技能、连接器状态三个视图——
 * 方法名即服务名，端点处理器经 {@code ctx.as(视图.class)} 惰性寻址。
 */
final class WebServiceViews {

    private WebServiceViews() {
    }

    /** 命令注册表视图接口（方法名即服务名 "commands"）。 */
    interface Commands {

        dev.duo.harness.agent.commands.CommandsRegistry commands();
    }

    /** 技能注册表视图接口（方法名即服务名 "skills"）。 */
    interface Skills {

        dev.duo.harness.agent.skills.SkillRegistry skills();
    }

    /** 连接器状态板的视图接口（服务名 connectorStatus，M24 工单 05）。 */
    interface ConnectorStatus {

        dev.duo.harness.tools.ConnectorStatusBoard connectorStatus();
    }
}
