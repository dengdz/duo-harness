package dev.duo.harness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.AuditingAnswerer;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.ContextGovernance;
import dev.duo.harness.agent.PlanMode;
import dev.duo.harness.agent.PromptFragment;
import dev.duo.harness.agent.PromptRegistry;
import dev.duo.harness.agent.Skill;
import dev.duo.harness.agent.SkillRegistry;
import dev.duo.harness.agent.presenter.PresenterAssembly;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolsService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CLI 呈现位插件（ADR-0011，与 WebPlugin 对称）：终端 REPL——LLM 自主调用工具
 * （Function Calling 经工具域三段管线与治理链）、HITL 交互（写操作 y/n 审批、
 * ask_user 提问）、技能直调（/技能名）、计划模式（/plan）。装配经共享装配器
 * （{@link PresenterAssembly}），呈现件是终端循环与 {@link ConsoleAnswerer}。
 *
 * <p><b>idle 语义</b>：`/exit` 或输入 EOF 只结束终端呈现——REPL 循环退出、会话
 * 独占锁释放、回答者摘除；**插件保持挂载、插件树与兄弟呈现位（如 Web）不受影响**。
 * 彻底停止交给进程信号——启动器的 shutdown hook 级联 dispose 调用 {@link #stop}。</p>
 *
 * <p>会话语义沿 M10-03：启动续接最新会话（独占锁），被占则明确提示并改开新会话
 * （绝不静默分脑）；`/new` 换绑即释放旧会话锁。LLM 未配置时插件 FAILED 并给出
 * 配置示例。</p>
 *
 * <p>线程约定：REPL 循环独占虚拟线程（cli-repl）；{@link #stop} 可从树 dispose
 * 线程并发调用（关输入流打断阻塞读 + 原子停止标志）；会话与回答者的清理在 idle
 * 收尾点单线程执行，stop 只负责打断。</p>
 *
 * <p>配置（块内字段可省，当前无项——呈现行为由终端自身决定）：
 * <pre>{@code config: {}}</pre></p>
 */
public final class CliPlugin implements Plugin<JsonNode> {

    private final BufferedReader in;
    private final PrintStream out;
    /** 会话目录（null = DuoHome 缺省 agent-sessions；测试注入临时目录）。 */
    private final Path sessionsDirOverride;
    /** 注入的 LLM 执行链（null = apply 时按 LlmConfig 装配；测试注入 mock）。 */
    private final LlmAdapter llmOverride;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private Thread replThread;
    /** 回答者注册的注销器（idle 与 stop 均摘除；幂等守卫见 #detachAnswerer）。 */
    private Disposable answererRegistration;
    private volatile boolean answererDetached;

    /** 生产构造：System.in/out + 配置装配。 */
    public CliPlugin() {
        this(new BufferedReader(new InputStreamReader(System.in)), System.out, null, null);
    }

    /**
     * 测试构造：脚本输入/输出、会话目录与 LLM 执行链全注入。
     *
     * @param llm 执行链（含重试）；null 则按 LlmConfig 装配（缺配置即 FAILED）
     */
    CliPlugin(BufferedReader in, PrintStream out, Path sessionsDir, LlmAdapter llm) {
        this.in = in;
        this.out = out;
        this.sessionsDirOverride = sessionsDir;
        this.llmOverride = llm;
    }

    @Override
    public Set<String> inject() {
        return Set.of(ToolsService.SERVICE_NAME, PromptRegistry.SERVICE_NAME,
                InteractionService.SERVICE_NAME, SkillRegistry.SERVICE_NAME);
    }

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        ToolsService tools = ctx.as(CliToolsView.class).tools();
        PromptRegistry prompts = ctx.as(CliPromptsView.class).prompts();
        InteractionService answers = ctx.as(CliAnswersView.class).answers();
        SkillRegistry skills = ctx.as(CliSkillsView.class).skills();

        LlmAdapter llm = llmOverride != null ? llmOverride : loadLlm();
        Path sessionsDir = sessionsDir();

        // 会话续接/新建（独占锁，M10-03）：被占则提示后改开新会话——绝不静默共享日志
        Session session;
        try {
            session = Session.latest(sessionsDir);
            if (session == null) {
                session = Session.create(sessionsDir);
            }
        } catch (dev.duo.harness.session.SessionLockedException e) {
            out.println("[提示] " + e.getMessage());
            out.println("[提示] 改为新建会话继续；被占会话仍由占用方使用。");
            session = Session.create(sessionsDir);
        }

        // 执行链与 HITL 供给（呈现位共享装配器）：治理、agent、回答者（审计桥包装）、交互工具
        ContextGovernance governance = PresenterAssembly.governance(llm);
        SessionHolder holder = new SessionHolder(session);
        ChatAgent agent = PresenterAssembly.chatAgent(llm, tools, session, prompts, governance);
        answererRegistration = answers.register(ctx,
                new AuditingAnswerer(holder::current, new ConsoleAnswerer(in, out)));
        PlanHolder plan = new PlanHolder();
        PresenterAssembly.registerInteractionTools(ctx, tools, answers, holder::current, () -> {
            plan.active = false;
            disposeGuidance(plan);
        });

        // 续接计划模式：激活态随会话恢复（指导片段重新挂上）
        plan.active = PlanMode.isActive(session);
        if (plan.active) {
            plan.guidance = prompts.register(ctx, new PromptFragment("plan:guidance", PlanMode.GUIDANCE));
            out.println("（续接会话：当前处于计划模式，/plan off 可退出）");
        }
        out.println("会话 " + session.id() + "（工具循环上下文）。/exit 退出，/new 开新话题。");
        out.flush();

        replThread = Thread.ofVirtual().name("cli-repl").start(() ->
                replLoop(ctx, llm, tools, prompts, governance, skills, holder, plan, agent));
        return this::stop;
    }

    /** 会话目录：注入优先，缺省 DuoHome 的 agent-sessions（apply 与 /new 共用）。 */
    private Path sessionsDir() {
        return sessionsDirOverride != null
                ? sessionsDirOverride : DuoHome.resolve().resolveDir("agent-sessions");
    }

    /** REPL 主循环（交互沿 AgentReplMain 既有形态）：读行 → 命令分发 → agent 执行。 */
    private void replLoop(Context ctx, LlmAdapter llm, ToolsService tools, PromptRegistry prompts,
                          ContextGovernance governance, SkillRegistry skills,
                          SessionHolder holder, PlanHolder plan, ChatAgent agent) {
        Path sessionsDir = sessionsDir();
        try {
            while (!stopped.get()) {
                out.print("你> ");
                out.flush();
                String line = in.readLine();
                if (line == null || line.strip().equals("/exit")) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                if (line.strip().equals("/new")) {
                    Session previous = holder.session;
                    holder.session = Session.create(sessionsDir);
                    agent = PresenterAssembly.chatAgent(llm, tools, holder.session, prompts, governance);
                    previous.close(); // 换绑即释放旧会话独占锁（本进程不再使用它）
                    plan.active = false;
                    disposeGuidance(plan);
                    out.println("新会话 " + holder.session.id() + "。");
                    out.flush();
                    continue;
                }
                String userText;
                if (line.strip().equals("/plan") || line.strip().startsWith("/plan ")) {
                    userText = handlePlanCommand(ctx, prompts, holder, plan,
                            line.strip().length() > 5 ? line.strip().substring(5).strip() : "");
                    if (userText == null) {
                        continue;
                    }
                } else {
                    userText = resolveSkillInvocation(line.strip(), skills);
                    if (userText == null) {
                        List<String> available = skills.all().stream().map(Skill::name).toList();
                        out.println("未知命令: " + line.strip().split("\\s+", 2)[0]
                                + (available.isEmpty() ? "" : "（可用技能: " + String.join(", ", available) + "）"));
                        out.flush();
                        continue;
                    }
                }
                try {
                    var reply = agent.send(userText, new AgentListener() {
                        @Override
                        public void onChunk(String text) {
                            out.print(text);
                            out.flush();
                        }

                        @Override
                        public void onToolCall(String toolName, String argumentsJson) {
                            out.println();
                            out.println("  [调工具] " + toolName + " " + argumentsJson);
                            out.flush();
                        }

                        @Override
                        public void onToolResult(String toolName, String resultText, boolean isError) {
                            out.println("  [工具" + (isError ? "错误] " : "结果] ") + resultText);
                            out.flush();
                        }
                    });
                    if (!reply.completed()) {
                        out.println("  [异常终止] " + reply.finalText());
                    }
                } catch (PluginException e) {
                    out.println("  [错误] " + e.getMessage());
                }
                out.println();
                out.flush();
            }
        } catch (IOException e) {
            // stop() 关闭输入流打断阻塞读——按 /exit 同语义收尾（idle）
        }
        goIdle(holder, plan);
    }

    /** /plan 与 /plan off：进入/退出计划模式；携带任务描述时按普通输入推进。返回 null = 已处理完毕。 */
    private String handlePlanCommand(Context ctx, PromptRegistry prompts,
                                     SessionHolder holder, PlanHolder plan, String rest) {
        if (rest.equals("off")) {
            if (plan.active) {
                holder.current().append(PlanMode.exitedEvent());
                disposeGuidance(plan);
                plan.active = false;
                out.println("已退出计划模式。");
            } else {
                out.println("当前不在计划模式。");
            }
            out.flush();
            return null;
        }
        if (plan.active) {
            out.println("已在计划模式中。");
            out.flush();
        } else {
            holder.current().append(PlanMode.enteredEvent());
            plan.active = true;
            plan.guidance = prompts.register(ctx, new PromptFragment("plan:guidance", PlanMode.GUIDANCE));
            out.println("已进入计划模式（先探索与设计，完成后调 exit_plan_mode 呈交计划；/plan off 退出）。");
            out.flush();
        }
        return rest.isEmpty() ? null : rest;
    }

    /** 摘除计划指导片段（Disposable 声明受检异常；失败不阻断流程）。 */
    private static void disposeGuidance(PlanHolder plan) {
        if (plan.guidance != null) {
            try {
                plan.guidance.dispose();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            plan.guidance = null;
        }
    }

    /** idle 收尾：循环退出（/exit、EOF 或 stop）后的统一清理——锁释放、回答者摘除、指导片段摘除。 */
    private void goIdle(SessionHolder holder, PlanHolder plan) {
        if (plan.guidance != null) {
            try {
                plan.guidance.dispose();
            } catch (Exception ignored) {
                // 树已停止时注销器可能失效，忽略
            }
            plan.guidance = null;
        }
        holder.current().close(); // 释放会话独占锁（他处可续接）
        detachAnswerer();
        out.println("=== 对话结束 ===");
        out.flush();
    }

    /** 插件停止（树 dispose / shutdown hook 调用）：打断阻塞读并按 idle 收尾。 */
    private void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            in.close(); // 解除 readLine 阻塞（System.in 的关闭无害——进程正在退出）
        } catch (IOException ignored) {
            // 已关
        }
        if (replThread != null) {
            replThread.interrupt();
        }
    }

    private synchronized void detachAnswerer() {
        if (!answererDetached && answererRegistration != null) {
            answererDetached = true;
            try {
                answererRegistration.dispose();
            } catch (Exception ignored) {
                // 树已停止时注销器可能失效，忽略
            }
        }
    }

    /** LLM 装配：配置缺失时 FAILED 并给出示例（沿 CLI 既有提示形态）。 */
    private static LlmAdapter loadLlm() {
        try {
            return PresenterAssembly.llmAdapter(LlmConfig.load());
        } catch (PluginException e) {
            throw new PluginException("CLI 面无法启动——LLM 未配置: " + e.getMessage()
                    + "\n示例（~/.duo/config.yml）:\n  llm:\n    baseUrl: https://api.deepseek.com"
                    + "\n    apiKey: <你的 key>\n    model: deepseek-chat", e);
        }
    }

    /** 可变引用：/new 时换会话（agent 与回答者的会话供给都经它取当前值）。 */
    private static final class SessionHolder {
        Session session;

        SessionHolder(Session session) {
            this.session = session;
        }

        Session current() {
            return session;
        }
    }

    /** 计划模式装配态：激活标志 + 指导片段的注销器（批准/退出时摘除）。 */
    private static final class PlanHolder {
        boolean active;
        Disposable guidance;
    }

    /**
     * 技能直调识别（用户直调路，M7 三路触发之三）：`/技能名 [其余输入]` →
     * 技能指令全文前缀注入（"指令\n\n用户输入：其余"）；未匹配技能名返回 null
     * （内置命令 /exit /new /plan 由调用方先行处理，优先于技能名）。
     */
    static String resolveSkillInvocation(String line, SkillRegistry skills) {
        if (!line.startsWith("/")) {
            return line;
        }
        String[] parts = line.split("\\s+", 2);
        Skill skill = skills.find(parts[0].substring(1));
        if (skill == null) {
            return null;
        }
        String rest = parts.length > 1 ? parts[1].strip() : "";
        return rest.isBlank() ? skill.content() : skill.content() + "\n\n用户输入：" + rest;
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface CliToolsView {

        ToolsService tools();
    }

    /** prompts 服务的视图接口（方法名即服务名 "prompts"）。 */
    interface CliPromptsView {

        PromptRegistry prompts();
    }

    /** 交互服务的视图接口（方法名即服务名 "answers"）。 */
    interface CliAnswersView {

        InteractionService answers();
    }

    /** 技能注册表的视图接口（方法名即服务名 "skills"）。 */
    interface CliSkillsView {

        SkillRegistry skills();
    }
}
