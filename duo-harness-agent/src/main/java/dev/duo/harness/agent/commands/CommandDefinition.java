package dev.duo.harness.agent.commands;

import java.util.Objects;
import java.util.function.Function;

/**
 * 命令定义（ADR-0020 决策 2）：name + description + 适用呈现位（缺省 ANY）+
 * busySafe（缺省 false，fail-closed）+ handler。
 *
 * <p>handler 同步执行于呈现位进程内、返回文本结果（进 command/done 审计与呈现位
 * 回显）；不占 agent 单飞窗口。命令异常由分发器收敛为错误说明文本，handler 无需
 * 自咽。名字不带斜杠（{@code "new"} 而非 {@code "/new"}），且不含空白——解析按
 * 首空白切分命令名与参数。</p>
 */
public final class CommandDefinition {

    private final String name;
    private final String description;
    private final CommandScope scope;
    private final boolean busySafe;
    private final Function<CommandContext, String> handler;

    /** 构造时校验（名字非空白且无空白字符）——错误前移到注册点。 */
    public CommandDefinition(String name, String description, CommandScope scope,
                             boolean busySafe, Function<CommandContext, String> handler) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(handler, "handler");
        String trimmed = name.strip();
        if (trimmed.isEmpty() || !trimmed.equals(name) || trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("命令名须为无空白的非空词: \"" + name + "\"");
        }
        this.name = trimmed;
        this.description = description == null ? "" : description;
        this.scope = scope == null ? CommandScope.ANY : scope;
        this.busySafe = busySafe;
        this.handler = handler;
    }

    /** 便捷工厂：ANY 适用面 + 非 busySafe（缺省语义）。 */
    public static CommandDefinition of(String name, String description,
                                       Function<CommandContext, String> handler) {
        return new CommandDefinition(name, description, CommandScope.ANY, false, handler);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public CommandScope scope() {
        return scope;
    }

    /** agent 单飞占用期间是否可立即执行（缺省 false——说不清就别在运行中动）。 */
    public boolean busySafe() {
        return busySafe;
    }

    public Function<CommandContext, String> handler() {
        return handler;
    }
}
