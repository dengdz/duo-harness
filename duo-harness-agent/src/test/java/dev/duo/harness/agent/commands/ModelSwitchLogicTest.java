package dev.duo.harness.agent.commands;

import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M38 工单 01 回归锁：模型/思考切换共享逻辑（PresenterAssembly.switchModel /
 * switchEffort）——白名单校验、换链状态推进、会话事件落盘；本呈现位独立的
 * 状态载体由呈现位控制器持有（web 面装配锁见 WebModelSwitchTest）。
 *
 * <p>env 鲁棒：根 pom surefire 注入 DUO_LLM_MODEL=test-model（env 优先于 yml），
 * 断言一律以「实际装载的生效模型」为锚、白名单两段式重写包含生效模型——不硬编码
 * 具体模型名。</p>
 */
class ModelSwitchLogicTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ModelSwitchLogicTest —— 切换逻辑：白名单/事件/换链状态推进（M38-01） ===");
    }

    @TempDir
    Path tempDir;

    /** 经 DuoHome 装载 LlmConfig（load 既有通道），可选重写 models 白名单。 */
    private dev.duo.harness.llm.LlmConfig loadConfig(String modelsFlow) throws Exception {
        Path home = tempDir.resolve("home-" + System.nanoTime());
        Files.createDirectories(home);
        String modelsLine = modelsFlow == null ? "" : "\n  models: " + modelsFlow;
        Files.writeString(home.resolve("config.yml"), """
                llm:
                  baseUrl: https://placeholder.local
                  apiKey: test-key
                  model: m-a%s
                """.formatted(modelsLine));
        String old = System.getProperty(DuoHome.PROP_OVERRIDE);
        System.setProperty(DuoHome.PROP_OVERRIDE, home.toString());
        try {
            return dev.duo.harness.llm.LlmConfig.load();
        } finally {
            if (old == null) {
                System.clearProperty(DuoHome.PROP_OVERRIDE);
            } else {
                System.setProperty(DuoHome.PROP_OVERRIDE, old);
            }
        }
    }

    /** 两段式：先读 env 生效模型，再写含生效模型的白名单重载（env 覆盖 yml model 的鲁棒形态）。 */
    private dev.duo.harness.llm.LlmConfig loadWithWhitelistContainingCurrent(String altModel) throws Exception {
        dev.duo.harness.llm.LlmConfig first = loadConfig(null);
        return loadConfig("[" + first.model() + ", " + altModel + "]");
    }

    @Test
    void switchModelRejectsOutsideWhitelist() throws Exception {
        var config = loadWithWhitelistContainingCurrent("m-alt");
        var swappable = new dev.duo.harness.llm.SwappableLlmAdapter(
                dev.duo.harness.agent.presenter.PresenterAssembly.llmAdapter(config));
        Session session = Session.create(tempDir.resolve("s1"));
        var result = dev.duo.harness.agent.presenter.PresenterAssembly.switchModel(
                config, swappable, "m-not-in-list", session);
        assertEquals(config.model(), result.next().model(), "拒切后配置不变");
        assertTrue(result.message().contains("白名单"), result.message());
        assertTrue(session.events().stream().noneMatch(e -> "model/intent".equals(e.type())),
                "拒切不落 model/intent 事件");
    }

    @Test
    void switchModelSwapsAndEmitsIntentEvent() throws Exception {
        var config = loadWithWhitelistContainingCurrent("m-alt");
        var swappable = new dev.duo.harness.llm.SwappableLlmAdapter(
                dev.duo.harness.agent.presenter.PresenterAssembly.llmAdapter(config));
        Session session = Session.create(tempDir.resolve("s2"));
        var result = dev.duo.harness.agent.presenter.PresenterAssembly.switchModel(
                config, swappable, "m-alt", session);
        assertEquals("m-alt", result.next().model(), "换链后新配置生效（下一轮语义的状态载体）");
        assertTrue(result.message().contains("已切换: m-alt"), result.message());
        assertTrue(session.events().stream().anyMatch(e ->
                        "model/intent".equals(e.type())),
                "model/intent 事件落会话");
    }

    @Test
    void switchModelSameValueIsNoop() throws Exception {
        var config = loadWithWhitelistContainingCurrent("m-alt");
        var swappable = new dev.duo.harness.llm.SwappableLlmAdapter(
                dev.duo.harness.agent.presenter.PresenterAssembly.llmAdapter(config));
        Session session = Session.create(tempDir.resolve("s3"));
        var result = dev.duo.harness.agent.presenter.PresenterAssembly.switchModel(
                config, swappable, config.model(), session);
        assertEquals(config.model(), result.next().model());
        assertTrue(result.message().contains("已是当前模型"), result.message());
    }

    @Test
    void switchEffortValidatesAndEmitsEvent() throws Exception {
        var config = loadWithWhitelistContainingCurrent("m-alt");
        var swappable = new dev.duo.harness.llm.SwappableLlmAdapter(
                dev.duo.harness.agent.presenter.PresenterAssembly.llmAdapter(config));
        Session session = Session.create(tempDir.resolve("s4"));
        var bad = dev.duo.harness.agent.presenter.PresenterAssembly.switchEffort(
                config, swappable, "ultra", session);
        assertTrue(bad.message().contains("非法档位"), bad.message());
        var ok = dev.duo.harness.agent.presenter.PresenterAssembly.switchEffort(
                config, swappable, "high", session);
        assertEquals("high", ok.next().effort(), "换链后新档位生效");
        assertTrue(session.events().stream().anyMatch(e ->
                        "model/effort".equals(e.type())),
                "model/effort 事件落会话");
        assertFalse(session.events().stream().anyMatch(e -> "model/intent".equals(e.type())),
                "effort 切换不落 model/intent");
    }
}
