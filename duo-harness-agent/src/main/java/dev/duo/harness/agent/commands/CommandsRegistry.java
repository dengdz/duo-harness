package dev.duo.harness.agent.commands;

import dev.duo.harness.agent.skills.Skill;
import dev.duo.harness.agent.skills.SkillRegistry;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 命令注册表（"commands" 服务，ADR-0020 决策 1）：插件代码注册斜杠命令，两呈现位
 * 共享同一入口顺序——命令注册表 → 技能直调（照旧进模型历史）→ 未知命令报错附可用
 * 命令清单。技能与命令两张表保持平行：语义分界 = 进不进模型历史（技能是"替用户
 * 说话"，命令是"操作 harness"）。
 *
 * <p>注册沿 {@code register(registrant, definition)} 惯例——注册即注册方作用域
 * effect，插件停止自动摘除；同名后到者装配即失败（代码装配重复是 bug，fail-fast，
 * 与技能扫描对环境数据宽容不同款）。执行语义：命令同步执行于呈现位进程内、返回
 * 文本结果；不进模型历史（{@code command/run} 与 {@code command/done} 两事件落
 * 会话日志，投影排除，崩溃断口可观测）；agent 单飞占用期间 busySafe 命令立即执行，
 * 其余明确回应"执行中，需等待空闲"（决策 4）。</p>
 */
public final class CommandsRegistry {

    /** 服务名（harness 保留裸名，与 prompts/skills 同居）。 */
    public static final String SERVICE_NAME = "commands";

    /** busySafe 拒绝回应（Web 409 的命令专用版；两呈现位同文）。 */
    public static final String BUSY_REFUSAL = "agent 执行中，需等待空闲后再执行该命令。";

    /** 已注册命令（注册序；CopyOnWrite 支撑分发时并发摘除）。 */
    private final List<CommandDefinition> commands = new CopyOnWriteArrayList<>();

    /**
     * 注册命令，随注册方作用域自动摘除。
     *
     * @param registrant 注册方 Context（其作用域销毁时命令自动摘除）
     * @param definition 命令定义
     * @return 注销器（手动提前摘除用）
     * @throws IllegalStateException 命令名已注册（装配期 fail-fast）
     */
    public Disposable register(Context registrant, CommandDefinition definition) {
        Objects.requireNonNull(registrant, "registrant");
        Objects.requireNonNull(definition, "definition");
        synchronized (commands) {
            if (find(definition.name()) != null) {
                throw new IllegalStateException("命令名已注册: /" + definition.name());
            }
            // 与 PromptRegistry.register 同模式：先挂注册方生命周期，再入列
            Disposable removal = registrant.effect(() -> commands.remove(definition));
            commands.add(definition);
            return removal;
        }
    }

    /** 按名查询（不带斜杠）；未注册返回 null。 */
    public CommandDefinition find(String name) {
        if (name == null) {
            return null;
        }
        for (CommandDefinition definition : commands) {
            if (definition.name().equals(name)) {
                return definition;
            }
        }
        return null;
    }

    /** 全部已注册命令（注册序）。 */
    public List<CommandDefinition> all() {
        return List.copyOf(commands);
    }

    /**
     * 共享入口（ADR-0020 决策 3）：命令注册表 → 技能直调 → 未知命令报错附可用清单。
     * 非斜杠输入原样透传。命中命令时按适用面 → busySafe 分级 → 落
     * {@code command/run} → 执行 → 落 {@code command/done} 的次序处理；命令异常
     * 收敛为错误说明文本（不影响 agent 单飞与呈现位存活）。
     *
     * @param rawInput 呈现位读到的整行输入（已去首尾空白）
     * @param env      呈现位分发环境（发起面、会话供给、回显、结束回调、单飞探针）
     * @param skills   技能注册表（入口顺序第二级；null 视为无技能）
     * @return 分发结果（命令已处理 / 透传文本）
     */
    public CommandOutcome dispatch(String rawInput, CommandEnv env, SkillRegistry skills) {
        Objects.requireNonNull(rawInput, "rawInput");
        Objects.requireNonNull(env, "env");
        if (!rawInput.startsWith("/")) {
            return CommandOutcome.prompt(rawInput);
        }
        String[] parts = rawInput.split("\\s+", 2);
        String name = parts[0].substring(1);
        String args = parts.length > 1 ? parts[1].strip() : "";

        CommandDefinition definition = find(name);
        if (definition == null) {
            return resolveSkillOrUnknown(rawInput, skills);
        }        if (!definition.scope().admits(env.presenter())) {
            return CommandOutcome.command("该命令仅在 " + definition.scope().displayName()
                    + " 可用。");
        }
        if (env.agentBusy().getAsBoolean() && !definition.busySafe()) {
            return CommandOutcome.command(BUSY_REFUSAL);
        }

        // 审计先行（run → 执行 → done）：崩溃断口可观测（run 落定而 done 缺席即中断点）。
        // run 落分发时的会话；done 落执行后的当前会话——/new 换绑后 done 随新会话开篇，
        // 换绑事实在两头日志都留痕。转发经收集器带出（命令语义的一部分，如 /plan 携任务描述）。
        Session started = env.session().get();
        started.append(SessionEvent.commandRun(definition.name(), args));
        AtomicReference<String> forwarded = new AtomicReference<>();
        CommandContext context = new CommandContext(args, env.session(), env.echo(),
                env.presenter(), env.requestEnd(), forwarded::set);
        String result;
        try {
            result = definition.handler().apply(context);
            if (result == null) {
                result = "";
            }
        } catch (Exception e) {
            // 命令异常收敛为回显文本：不影响 agent 单飞与呈现位存活（ADR-0020 Consequences）
            result = "命令执行失败: " + e.getMessage();
        }
        env.session().get().append(SessionEvent.commandDone(definition.name(), result));
        return forwarded.get() == null
                ? CommandOutcome.command(result)
                : CommandOutcome.commandThenForward(result, forwarded.get());
    }

    /** 入口顺序第二级：技能直调命中即注入透传；未命中任何表则报未知命令附可用清单。 */
    private CommandOutcome resolveSkillOrUnknown(String rawInput, SkillRegistry skills) {
        String[] parts = rawInput.split("\\s+", 2);
        Skill skill = skills == null ? null : skills.find(parts[0].substring(1));
        if (skill != null) {
            String rest = parts.length > 1 ? parts[1].strip() : "";
            return CommandOutcome.prompt(rest.isBlank()
                    ? skill.content() : skill.content() + "\n\n用户输入：" + rest);
        }
        return CommandOutcome.command("未知命令: " + parts[0] + availabilityDigest(skills));
    }

    /** 未知命令提示的可用清单摘要：命令清单必有，技能清单在场时附注。 */
    private String availabilityDigest(SkillRegistry skills) {
        String commandNames = all().stream().map(CommandDefinition::name)
                .collect(Collectors.joining(", "));
        String digest = commandNames.isEmpty() ? "（无可用命令" : "（可用命令: " + commandNames;
        if (skills != null && !skills.all().isEmpty()) {
            digest += "；可用技能: " + skills.all().stream().map(Skill::name)
                    .collect(Collectors.joining(", "));
        }
        return digest + "）";
    }
}
