package dev.duo.harness.agent.commands;

import java.util.Objects;

/**
 * 分发结果（ADR-0020 决策 3 的出口形态）：命令已处理（携结果回显文本与可选的
 * agent 转发文本）或透传给 agent 的输入（普通消息 / 技能直调注入文本——进模型历史）。
 */
public final class CommandOutcome {

    private final boolean command;
    private final String text;
    private final String forward;

    private CommandOutcome(boolean command, String text, String forward) {
        this.command = command;
        this.text = text;
        this.forward = forward;
    }

    /** 命令已处理：text 为结果回显文本（可为空串——如 /exit 无话可说）。 */
    public static CommandOutcome command(String resultText) {
        return new CommandOutcome(true, resultText == null ? "" : resultText, null);
    }

    /** 命令已处理且附带转发文本（/plan 携任务描述同款：先回显结果再推进 agent）。 */
    public static CommandOutcome commandThenForward(String resultText, String forwardText) {
        Objects.requireNonNull(forwardText, "forwardText");
        return new CommandOutcome(true, resultText == null ? "" : resultText, forwardText);
    }

    /** 透传给 agent 的输入（普通消息或技能直调注入文本）。 */
    public static CommandOutcome prompt(String inputText) {
        return new CommandOutcome(false, inputText, null);
    }

    /** true = 命令已处理（呈现位无须调 agent）；false = text 是给 agent 的输入。 */
    public boolean isCommand() {
        return command;
    }

    /** 命令结果回显文本（isCommand 时）；或给 agent 的输入文本（非 command 时）。 */
    public String text() {
        return text;
    }

    /** 命令处理完成后再推进 agent 的输入文本（无转发为 null）。 */
    public String forward() {
        return forward;
    }
}
