package dev.duo.harness.example.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.agent.presenter.PresenterAssembly;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionLockedException;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolsService;
import dev.duo.harness.agent.prompt.PromptRegistry;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * headless 集成段（M23 工单 07）：装配 yml 预过滤（呈现位行 disabled——headless
 * 自身即呈现位，cli REPL 与 web 服务器不启动，其余行原样装载）→ Boot 装树 →
 * 会话创建/恢复 → {@link HeadlessRunner} 执行。
 *
 * <p>预过滤副本而非 Boot 改造：disabled 只在 BootLoader 装载层生效（M23 探测结论），
 * 契约层无行过滤重载——读 yml 改写副本是仓库既有惯例（DemoYml.ephemeralPortCopy）。
 * SIGTERM 拦截为 {@code System.exit(0)}（shutdown hook 先行级联 dispose、确定性
 * 释放会话锁后以 0 退出）；SIGINT 不拦截——JVM 默认终止码即 130（契约天然成立，
 * 与 CliPlugin 拦截注释同源）。</p>
 */
public final class HeadlessBoot {

    /** headless 模式下禁用的呈现位行（headless 自身即第三个呈现位）——
     * 类名取自类引用，改名编译期跟随（yml 行 name 即 FQCN）。 */
    static final java.util.List<String> PRESENTER_ROWS = java.util.List.of(
            dev.duo.harness.cli.CliPlugin.class.getName(),
            dev.duo.harness.web.WebPlugin.class.getName());

    private HeadlessBoot() {
    }

    /**
     * 跑 headless 任务全流程。
     *
     * @return 进程退出码：0 成功；1 任务失败/会话不可用；2 usage 错误（调用方处理）
     */
    public static int run(Path yml, String sessionId, String prompt) throws Exception {
        interceptSigterm();
        Path filtered = filteredCopy(yml);
        Context root = Boot.from(filtered);
        Thread hook = new Thread(root::dispose, "duo-headless-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        Session session = null;
        try {
            ToolsService tools = root.as(HeadlessToolsView.class).tools();
            PromptRegistry prompts = root.as(HeadlessPromptsView.class).prompts();
            InteractionService answers = root.as(HeadlessAnswersView.class).answers();
            session = openSession(sessionId);
            LlmConfig cfg = LlmConfig.load();
            HeadlessRunner.Services services = new HeadlessRunner.Services(root,
                    PresenterAssembly.llmAdapter(cfg), tools, prompts, answers, session,
                    System.out, System.err);
            return HeadlessRunner.run(services, prompt, PresenterAssembly.parseMaxIterations(null));
        } finally {
            // Session 不在 Boot 树内——显式 close 释放独占锁（兜底：进程退出 OS 亦回收）
            if (session != null) {
                session.close();
            }
            root.dispose(); // 兜底幂等：树资源与插件生命周期收尾
        }
    }

    /** 会话创建或恢复（--session-id）；不可用（被占/不存在）以退出码 1 明确失败。 */
    private static Session openSession(String sessionId) {
        Path sessionsDir = DuoHome.resolve().resolveDir("agent-sessions");
        try {
            if (sessionId == null) {
                return Session.create(sessionsDir);
            }
            Path jsonl = sessionsDir.resolve(sessionId + ".jsonl");
            if (!Files.isRegularFile(jsonl)) {
                return sessionUnavailable("会话不存在: " + sessionId);
            }
            return Session.load(jsonl);
        } catch (SessionLockedException e) {
            return sessionUnavailable("会话被占用: " + sessionId);
        }
    }

    /** 会话不可用收口：error 帧 + final 帧（final 必发契约兜底）+ 退出码 1。 */
    private static Session sessionUnavailable(String reason) {
        System.err.println("[headless] " + reason);
        System.out.println(NdjsonFrames.frame("error", NdjsonFrames.fields("message", reason)));
        System.out.println(NdjsonFrames.finalFrame("会话不可用——" + reason));
        Runtime.getRuntime().exit(1);
        return null; // 不可达：exit 在上
    }

    /** 装配 yml 预过滤副本：呈现位行（cli/web）标 {@code disabled: true}，余行原样。 */
    static Path filteredCopy(Path yml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        ObjectNode tree = (ObjectNode) mapper.readTree(Files.readString(yml));
        JsonNode plugins = tree.get("plugins");
        if (plugins instanceof ArrayNode rows) {
            for (JsonNode row : rows) {
                if (row instanceof ObjectNode pluginRow
                        && PRESENTER_ROWS.contains(pluginRow.path("name").asText())) {
                    pluginRow.put("disabled", true);
                }
            }
        }
        Path copy = Files.createTempFile("duo-headless-", ".yml");
        Files.writeString(copy, mapper.writeValueAsString(tree));
        copy.toFile().deleteOnExit();
        return copy;
    }

    /** SIGTERM → exit(0)：shutdown hook（级联 dispose、释放会话锁）先行后以 0 退出。 */
    private static void interceptSigterm() {
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("TERM"),
                    sig -> Runtime.getRuntime().exit(0));
        } catch (Throwable ignored) {
            // 信号拦截不可用（非 HotSpot 等）：保留 JVM 默认终止——dispose 仍由 hook 保证
        }
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface HeadlessToolsView {

        ToolsService tools();
    }

    /** prompts 服务的视图接口（方法名即服务名 "prompts"）。 */
    interface HeadlessPromptsView {

        PromptRegistry prompts();
    }

    /** 交互服务的视图接口（方法名即服务名 "answers"）。 */
    interface HeadlessAnswersView {

        InteractionService answers();
    }
}
