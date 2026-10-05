package dev.duo.harness.web;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.agent.commands.CommandScope;
import dev.duo.harness.agent.commands.CommandEnv;
import dev.duo.harness.agent.commands.CommandsRegistry;
import dev.duo.harness.agent.commands.ModelSwitchRegistry;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M38 工单 01 回归锁：Web 面换链绑定与 /model、/effort 升 ANY——WEB 发起分发经
 * 登记表命中 Web 控制器（换链 + 事件），CLI 发起在同一装配下独立（未装配 → 不支持
 * 文案，「本呈现位独立」裁定）；命令 scope 断言。
 */
class WebModelSwitchTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebModelSwitchTest —— Web 换链绑定：ANY 双面分发/事件/本呈现位独立（M38-01） ===");
    }

    @TempDir
    Path tempDir;

    /** 装配纯 Web 插件树（config.yml 带 models 白名单），返回根上下文与会话供给。 */
    private record Assembled(Context root, CommandsRegistry commands,
                             ModelSwitchRegistry registry, Session session) {}

    interface CommandsView {

        CommandsRegistry commands();
    }

    interface RegistryView {

        ModelSwitchRegistry modelSwitch();
    }

    private Assembled assemble() throws Exception {
        Path home = tempDir.resolve("duo-home-" + System.nanoTime());
        Files.createDirectories(home);
        Files.writeString(home.resolve("config.yml"), """
                llm:
                  baseUrl: https://placeholder.local
                  apiKey: test-key
                  model: m-a
                  models: [m-a, m-b]
                """);
        System.setProperty(DuoHome.PROP_OVERRIDE, home.toString());
        Context root = Context.root();
        JsonNodeFactory factory = JsonNodeFactory.instance;
        root.plugin(new dev.duo.harness.tools.ToolsPlugin(), null).awaitStartup();
        root.plugin(new dev.duo.harness.agent.prompt.PromptPlugin(), factory.objectNode()).awaitStartup();
        root.plugin(new dev.duo.harness.tools.InteractionPlugin(), null).awaitStartup();
        root.plugin(new dev.duo.harness.agent.commands.CommandsPlugin(), factory.objectNode()).awaitStartup();
        WebPlugin web = new WebPlugin();
        root.plugin(web, factory.objectNode().put("port", 0)).awaitStartup();
        return new Assembled(root,
                root.as(CommandsView.class).commands(),
                root.as(RegistryView.class).modelSwitch(),
                Session.create(tempDir.resolve("session")));
    }

    @Test
    void webDispatchSwitchesWebChainAndEmitsEvent(@TempDir Path unused) throws Exception {
        Assembled assembled = assemble();
        try {
            // scope 断言：/model、/effort 升 ANY（双面）
            assertEquals(CommandScope.ANY, assembled.commands().find("model").scope(),
                    "/model 升双面（ADR-0040 决策三）");
            assertEquals(CommandScope.ANY, assembled.commands().find("effort").scope(),
                    "/effort 升双面");
            // WEB 发起：换链 + 事件
            var outcome = assembled.commands().dispatch("/model m-b",
                    new CommandEnv(CommandScope.WEB, () -> assembled.session(),
                            s -> { }, () -> { }, () -> false), null);
            assertTrue(outcome.isCommand(), "斜杠命中命令");
            assertTrue(outcome.text().contains("已切换: m-b"), outcome.text());
            assertTrue(assembled.session().events().stream().anyMatch(e ->
                            "model/intent".equals(e.type()) && e.text().contains("m-b")),
                    "model/intent 事件落会话");
            // 换链后查看：当前模型 m-b（控制器状态推进）
            var view = assembled.commands().dispatch("/model",
                    new CommandEnv(CommandScope.WEB, () -> assembled.session(),
                            s -> { }, () -> { }, () -> false), null);
            assertTrue(view.text().contains("当前模型: m-b"), view.text());
            // effort 经 WEB 分发：换档 + model/effort 事件（轴二 4 半缺项补齐）
            var effortOutcome = assembled.commands().dispatch("/effort high",
                    new CommandEnv(CommandScope.WEB, () -> assembled.session(),
                            s -> { }, () -> { }, () -> false), null);
            assertTrue(effortOutcome.text().contains("已切换: high"), effortOutcome.text());
            assertTrue(assembled.session().events().stream().anyMatch(e ->
                            "model/effort".equals(e.type())),
                    "model/effort 事件落会话");
            // 本呈现位独立：同一装配下 CLI 发起 → CLI 控制器未登记 → 不支持文案（不碰 Web 链）
            var cliSide = assembled.commands().dispatch("/model",
                    new CommandEnv(CommandScope.CLI, () -> assembled.session(),
                            s -> { }, () -> { }, () -> false), null);
            assertTrue(cliSide.text().contains("未装配可切换执行链"), cliSide.text());
            // Web 链未被 CLI 发起波及
            var webAgain = assembled.commands().dispatch("/model",
                    new CommandEnv(CommandScope.WEB, () -> assembled.session(),
                            s -> { }, () -> { }, () -> false), null);
            assertTrue(webAgain.text().contains("当前模型: m-b"), webAgain.text());
        } finally {
            if (assembled != null) {
                assembled.root().dispose();
                System.clearProperty(DuoHome.PROP_OVERRIDE);
            }
        }
    }

    @Test
    void unknownModelRejectedViaDispatch() throws Exception {
        Assembled assembled = assemble();
        try {
            var outcome = assembled.commands().dispatch("/model m-x",
                    new CommandEnv(CommandScope.WEB, () -> assembled.session(),
                            s -> { }, () -> { }, () -> false), null);
            assertTrue(outcome.text().contains("白名单"), outcome.text());
            assertTrue(assembled.session().events().stream().noneMatch(e ->
                    "model/intent".equals(e.type())), "拒切不落事件");
        } finally {
            if (assembled != null) {
                assembled.root().dispose();
                System.clearProperty(DuoHome.PROP_OVERRIDE);
            }
        }
    }
}
