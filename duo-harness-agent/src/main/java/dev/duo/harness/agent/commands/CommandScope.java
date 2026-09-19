package dev.duo.harness.agent.commands;

/**
 * 命令适用呈现位（ADR-0020 决策 2）：声明一条命令在哪些呈现面可敲。
 * ANY 缺省（两端可用）；CLI/WEB 收敛到单一呈现面，不符面得到
 * "该命令仅在 X 可用" 的明确提示而非静默吞掉。
 */
public enum CommandScope {

    /** 任意呈现位（缺省）。 */
    ANY("任意呈现位"),

    /** 仅终端呈现位。 */
    CLI("CLI"),

    /** 仅 Web 呈现位。 */
    WEB("Web");

    private final String displayName;

    CommandScope(String displayName) {
        this.displayName = displayName;
    }

    /** 人读名（适用面不符提示用）。 */
    public String displayName() {
        return displayName;
    }

    /** 该命令对发起呈现位 origin 是否可用（ANY 恒真）。 */
    public boolean admits(CommandScope origin) {
        return this == ANY || this == origin;
    }
}
