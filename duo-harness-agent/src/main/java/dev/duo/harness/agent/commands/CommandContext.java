package dev.duo.harness.agent.commands;

import dev.duo.harness.session.Session;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 命令执行上下文（ADR-0020 决策 2）：handler 的统一供给——参数文本、当前会话、
 * 回显通道、发起呈现位、两个回调（请求结束 / 转发 agent 输入）。
 *
 * <p>由 {@link CommandsRegistry#dispatch} 在分发时构造，handler 同步执行完毕即废弃；
 * 会话走供给函数（Supplier）而非实例引用——/new 换绑后 handler 经同一上下文取到的
 * 永远是当前会话（闭包捕旧引用的换绑坑由供给语义根除）。</p>
 *
 * <p>{@link #forward(String)} 是命令语义的一部分（如 /plan 携带任务描述 = 进计划模式
 * 并把描述推进 agent），转发文本作为普通用户输入走 agent.send——进模型历史、落
 * user/message，与手敲无异。</p>
 */
public final class CommandContext {

    private final String args;
    private final Supplier<Session> session;
    private final Consumer<String> echo;
    private final CommandScope presenter;
    private final Runnable requestEnd;
    private final Consumer<String> forward;

    /** 分发器构造（handler 只读）。 */
    CommandContext(String args, Supplier<Session> session, Consumer<String> echo,
                   CommandScope presenter, Runnable requestEnd, Consumer<String> forward) {
        this.args = args;
        this.session = session;
        this.echo = echo;
        this.presenter = presenter;
        this.requestEnd = requestEnd;
        this.forward = forward;
    }

    /** 命令参数文本（命令名之后的其余输入，已去首尾空白；无参数为空串）。 */
    public String args() {
        return args;
    }

    /** 当前会话（每次调用经供给函数取现值——换绑感知）。 */
    public Session session() {
        return session.get();
    }

    /** 回显通道（增量输出用；命令的结果文本走返回值，不经此通道）。 */
    public void echo(String text) {
        echo.accept(text);
    }

    /** 发起呈现位（handler 内按发起面分流的判断依据）。 */
    public CommandScope presenter() {
        return presenter;
    }

    /** 请求结束回调（/exit 同款）：呈现位收到后结束本轮交互循环，语义由呈现位定义。 */
    public void requestEnd() {
        requestEnd.run();
    }

    /** 转发文本给 agent（普通用户输入语义：进模型历史、落 user/message）。 */
    public void forward(String text) {
        Objects.requireNonNull(text, "text");
        forward.accept(text);
    }
}
