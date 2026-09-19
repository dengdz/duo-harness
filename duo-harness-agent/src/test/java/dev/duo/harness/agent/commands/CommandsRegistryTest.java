package dev.duo.harness.agent.commands;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.agent.skills.SkillRegistry;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令注册表用例（M19 工单 01，缝 1，ADR-0020）：注册/作用域摘除、同名 fail-fast、
 * 共享入口顺序（命令 → 技能直调 → 未知报错）、适用面、busySafe 分级、异常收敛、
 * 审计两事件落盘与换绑后 done 落新会话。只断言外部行为（事件形态、结果文本、
 * 回调触发）。
 */
class CommandsRegistryTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：CommandsRegistryTest —— 命令注册表：注册与作用域摘除、"
                + "同名 fail-fast、入口顺序（命令/技能直调/未知清单）、适用面、busySafe 分级、"
                + "异常收敛、run+done 审计与换绑落新会话（10 用例） ===");
    }

    @TempDir
    Path tempDir;

    private Path sessionsDir() {
        return tempDir.resolve("sessions");
    }

    /** 空环境（无 busy、无结束请求）：会话供给指向给定目录的最新用法由用例自建。 */
    private CommandEnv env(CommandScope presenter, Session session) {
        return new CommandEnv(presenter, () -> session, s -> { }, () -> { }, () -> false);
    }

    private CommandDefinition echoCommand(String name) {
        return CommandDefinition.of(name, "回显参数", context -> "echo:" + context.args());
    }

    @Test
    void registerAddsAndRegistrantScopeRemoves() {
        // 注册沿 register(registrant, definition) 惯例：注册方作用域销毁即自动摘除
        Context registrant = Context.root();
        CommandsRegistry registry = new CommandsRegistry();
        try {
            registry.register(registrant, echoCommand("greet"));
            registry.register(registrant, CommandDefinition.of("bye", "道别", context -> "再见"));
            assertEquals(2, registry.all().size());
            assertEquals("greet", registry.find("greet").name());

            registrant.dispose();
            assertNull(registry.find("greet"), "作用域销毁即摘除");
            assertNull(registry.find("bye"));
            assertEquals(0, registry.all().size());
        } finally {
            registrant.dispose();
        }
    }

    @Test
    void duplicateNameFailsFastAtRegistration() {
        Context registrant = Context.root();
        CommandsRegistry registry = new CommandsRegistry();
        try {
            registry.register(registrant, echoCommand("new"));
            assertThrows(IllegalStateException.class, () -> registry.register(registrant,
                    echoCommand("new")), "同名注册是装配 bug，fail-fast");
        } finally {
            registrant.dispose();
        }
    }

    @Test
    void plainInputPassesThroughWithoutAudit() {
        Session session = Session.create(sessionsDir());
        CommandsRegistry registry = new CommandsRegistry();
        CommandOutcome outcome = registry.dispatch("普通问题", env(CommandScope.CLI, session), null);

        assertFalse(outcome.isCommand());
        assertEquals("普通问题", outcome.text());
        assertTrue(session.events().isEmpty(), "透传不落命令审计事件");
        session.close();
    }

    @Test
    void dispatchedCommandRunsHandlerAndAuditsRunDone() {
        // 命令命中：handler 结果即回显文本，run/done 成对落盘（先 run 后 done）
        Session session = Session.create(sessionsDir());
        CommandsRegistry registry = new CommandsRegistry();
        Context registrant = stubRegistrant();
        registry.register(registrant, echoCommand("greet"));
        CommandOutcome outcome = registry.dispatch("/greet 世界",
                env(CommandScope.CLI, session), null);

        assertTrue(outcome.isCommand());
        assertEquals("echo:世界", outcome.text());
        List<SessionEvent> events = session.events();
        assertEquals(2, events.size());
        assertEquals(SessionEvent.COMMAND_RUN, events.get(0).type());
        assertEquals("greet", events.get(0).toolName(), "命令名走工具名可选位");
        assertEquals("世界", events.get(0).text(), "参数文本走载荷位");
        assertEquals(SessionEvent.COMMAND_DONE, events.get(1).type());
        assertEquals("echo:世界", events.get(1).text());
        registrant.dispose();
        session.close();
    }

    @Test
    void skillInvocationTakesSecondSeatAndInjectsPrefix() throws IOException {
        // 入口顺序第二级（ADR-0020 决策 3）：命令表未命中、技能命中 → 指令全文前缀
        // 注入透传（进模型历史）；带其余输入拼 "用户输入：" 段——CLI 原直调语义不变
        Path skillDir = tempDir.resolve("release-notes");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: release-notes\ndescription: 生成发布说明\n---\n请按仓库规范撰写发布说明。");
        SkillRegistry skills = SkillRegistry.scan(List.of(tempDir), java.util.Set.of());

        Session session = Session.create(sessionsDir());
        CommandsRegistry registry = new CommandsRegistry();

        CommandOutcome withArgs = registry.dispatch("/release-notes 0.3.0",
                env(CommandScope.CLI, session), skills);
        assertFalse(withArgs.isCommand());
        assertEquals("请按仓库规范撰写发布说明。\n\n用户输入：0.3.0", withArgs.text());

        CommandOutcome bare = registry.dispatch("/release-notes",
                env(CommandScope.CLI, session), skills);
        assertEquals("请按仓库规范撰写发布说明。", bare.text());
        assertTrue(session.events().isEmpty(), "技能直调进模型历史，不经命令审计");
        session.close();
    }

    @Test
    void unknownCommandListsAvailableCommands() {
        Session session = Session.create(sessionsDir());
        CommandsRegistry registry = new CommandsRegistry();
        Context registrant = stubRegistrant();
        registry.register(registrant, echoCommand("new"));
        registry.register(registrant, echoCommand("permission"));

        CommandOutcome outcome = registry.dispatch("/不存在", env(CommandScope.CLI, session), null);

        assertTrue(outcome.isCommand());
        assertEquals("未知命令: /不存在（可用命令: new, permission）", outcome.text());
        assertTrue(session.events().isEmpty(), "未知命令未执行任何命令，无审计事件");
        registrant.dispose();
        session.close();
    }

    @Test
    void scopeMismatchRefusesWithHintAndMatchRuns() {
        // 适用面（ADR-0020 决策 2）：WEB 专属命令从 CLI 敲 → 明确提示；从 WEB 敲 → 执行
        Session session = Session.create(sessionsDir());
        CommandsRegistry registry = new CommandsRegistry();
        Context registrant = stubRegistrant();
        registry.register(registrant, new CommandDefinition("web-only", "Web 专属",
                CommandScope.WEB, false, context -> "web 执行了"));

        CommandOutcome refused = registry.dispatch("/web-only",
                env(CommandScope.CLI, session), null);
        assertTrue(refused.isCommand());
        assertEquals("该命令仅在 Web 可用。", refused.text());
        assertTrue(session.events().isEmpty(), "适用面不符未执行，无审计事件");

        CommandOutcome admitted = registry.dispatch("/web-only",
                env(CommandScope.WEB, session), null);
        assertEquals("web 执行了", admitted.text());
        registrant.dispose();
        session.close();
    }

    @Test
    void busyAgentRefusesNonSafeAndAdmitsSafeCommands() {
        // busySafe 分级（ADR-0020 决策 4）：agent 单飞占用时非 busySafe 明确等待提示；
        // busySafe（如 /permission 切档）立即执行。空闲时全部放行
        Session session = Session.create(sessionsDir());
        CommandsRegistry registry = new CommandsRegistry();
        Context registrant = stubRegistrant();
        registry.register(registrant, echoCommand("new"));
        registry.register(registrant, new CommandDefinition("permission", "切档",
                CommandScope.CLI, true, context -> "已切换"));

        AtomicBoolean busy = new AtomicBoolean(true);
        CommandEnv busyEnv = new CommandEnv(CommandScope.CLI, () -> session, s -> { },
                () -> { }, busy::get);
        assertEquals(CommandsRegistry.BUSY_REFUSAL,
                registry.dispatch("/new", busyEnv, null).text());
        assertEquals("已切换", registry.dispatch("/permission workspace-write", busyEnv, null).text());

        busy.set(false);
        assertEquals("echo:", registry.dispatch("/new", busyEnv, null).text(),
                "空闲后非 busySafe 照常执行");
        registrant.dispose();
        session.close();
    }

    @Test
    void handlerExceptionConvergesToResultText() {
        // 命令异常收敛为回显文本（ADR-0020 Consequences）：不外抛、done 照常落盘
        Session session = Session.create(sessionsDir());
        CommandsRegistry registry = new CommandsRegistry();
        Context registrant = stubRegistrant();
        registry.register(registrant, CommandDefinition.of("boom", "会炸", context -> {
            throw new IllegalStateException("爆了");
        }));

        CommandOutcome outcome = registry.dispatch("/boom", env(CommandScope.CLI, session), null);
        assertTrue(outcome.isCommand());
        assertEquals("命令执行失败: 爆了", outcome.text());
        List<SessionEvent> events = session.events();
        assertEquals(2, events.size());
        assertEquals("命令执行失败: 爆了", events.get(1).text(), "错误说明即 done 结果");
        registrant.dispose();
        session.close();
    }

    @Test
    void forwardAndRequestEndRideThroughContext() {
        // 两个回调（ADR-0020 决策 2）：/exit 同款结束请求经 requestEnd 表达；
        // /plan 携任务描述的转发经 forward 带出为 commandThenForward
        Session session = Session.create(sessionsDir());
        CommandsRegistry registry = new CommandsRegistry();
        Context registrant = stubRegistrant();
        AtomicBoolean ended = new AtomicBoolean(false);
        registry.register(registrant, new CommandDefinition("exit", "退出",
                CommandScope.CLI, false, context -> {
                context.requestEnd();
                return "";
            }));
        registry.register(registrant, new CommandDefinition("plan", "计划",
                CommandScope.CLI, false, context -> {
                context.forward("任务描述");
                return "已进入计划模式。";
            }));

        CommandEnv exitEnv = new CommandEnv(CommandScope.CLI, () -> session, s -> { },
                () -> ended.set(true), () -> false);
        CommandOutcome exit = registry.dispatch("/exit", exitEnv, null);
        assertTrue(ended.get(), "requestEnd 回调已触发");
        assertTrue(exit.isCommand());
        assertEquals("", exit.text(), "/exit 无话可说");
        assertNull(exit.forward());

        CommandOutcome plan = registry.dispatch("/plan 任务描述",
                env(CommandScope.CLI, session), null);
        assertEquals("已进入计划模式。", plan.text());
        assertEquals("任务描述", plan.forward(), "转发文本由呈现位交 agent.send");
        registrant.dispose();
        session.close();
    }

    @Test
    void doneLandsOnReboundSessionWhileRunStaysOnOld() {
        // /new 换绑审计形态：run 落分发时会话（旧），done 落执行后当前会话（新）——
        // 换绑事实在两头日志留痕，旧会话以 run 收尾即断口可观测
        Session first = Session.create(sessionsDir());
        Session second = Session.create(sessionsDir());
        AtomicReference<Session> current = new AtomicReference<>(first);
        CommandEnv env = new CommandEnv(CommandScope.CLI, current::get, s -> { },
                () -> { }, () -> false);

        CommandsRegistry registry = new CommandsRegistry();
        Context registrant = stubRegistrant();
        registry.register(registrant, new CommandDefinition("new", "换绑", CommandScope.CLI,
                false, context -> {
                    current.set(second); // handler 内换绑（真实 /new 还会 close 旧会话）
                    return "新会话。";
                }));

        CommandOutcome outcome = registry.dispatch("/new", env, null);
        assertEquals("新会话。", outcome.text());

        assertEquals(1, first.events().size());
        assertEquals(SessionEvent.COMMAND_RUN, first.events().get(0).type(), "run 落旧会话");
        assertEquals(1, second.events().size());
        assertEquals(SessionEvent.COMMAND_DONE, second.events().get(0).type(), "done 随新会话开篇");
        registrant.dispose();
        first.close();
        second.close();
    }

    /** 注册方桩：仅作 register 的生命周期宿主（手动 dispose 清场）。 */
    private Context stubRegistrant() {
        return Context.root();
    }
}
