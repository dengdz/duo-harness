package dev.duo.harness.agent.commands;

import dev.duo.harness.session.Session;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 呈现位分发环境（ADR-0020 决策 2/3）：呈现位调用 {@link CommandsRegistry#dispatch}
 * 时的一次性接线——发起呈现位标记、当前会话供给、回显通道、请求结束回调与 agent
 * 单飞探针。会话走供给函数：/new 换绑后分发器经同一环境取到的永远是当前会话。
 *
 * @param presenter  发起呈现位标记（适用面判定的比对基准）
 * @param session    当前会话供给（command/run|done 事件的落点与 handler 的会话来源）
 * @param echo       回显通道（CommandContext#echo 的后端；CLI 为终端行、Web 为响应流）
 * @param requestEnd 请求结束回调（/exit 同款命令经 CommandContext#requestEnd 表达）
 * @param agentBusy  agent 单飞探针（true = 执行中；busySafe 分级的判定输入）
 */
public record CommandEnv(CommandScope presenter, Supplier<Session> session,
                         Consumer<String> echo, Runnable requestEnd,
                         BooleanSupplier agentBusy) {

    public CommandEnv {
        Objects.requireNonNull(presenter, "presenter");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(echo, "echo");
        Objects.requireNonNull(requestEnd, "requestEnd");
        Objects.requireNonNull(agentBusy, "agentBusy");
    }
}
