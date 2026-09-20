package dev.duo.harness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.AuditingAnswerer;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.commands.CommandContext;
import dev.duo.harness.agent.commands.CommandDefinition;
import dev.duo.harness.agent.commands.CommandEnv;
import dev.duo.harness.agent.commands.CommandOutcome;
import dev.duo.harness.agent.commands.CommandScope;
import dev.duo.harness.agent.commands.CommandsRegistry;
import dev.duo.harness.agent.governance.ContextGovernance;
import dev.duo.harness.agent.SessionTitles;
import dev.duo.harness.agent.plan.PlanMode;
import dev.duo.harness.agent.prompt.PromptFragment;
import dev.duo.harness.agent.prompt.PromptRegistry;
import dev.duo.harness.agent.skills.SkillRegistry;
import dev.duo.harness.agent.presenter.PresenterAssembly;
import dev.duo.harness.agent.todo.TodoWriteTool;
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
import dev.duo.harness.tools.fs.WorkspacePolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CLI 呈现位插件（ADR-0011，与 WebPlugin 对称）：终端 REPL——LLM 自主调用工具
 * （Function Calling 经工具域三段管线与治理链）、HITL 交互（写操作 y/n 审批、
 * ask_user 提问）、斜杠命令（M19 起经命令注册表共享入口：/new /permission /plan
 * /exit 四命令注册为 CLI 适用面）、技能直调（/技能名，注册表入口第二级）。
 * 装配经共享装配器（{@link PresenterAssembly}），呈现件是终端循环与
 * {@link ConsoleAnswerer}。
 *
 * <p><b>idle 语义</b>：`/exit` 或输入 EOF 只结束终端呈现——REPL 循环退出、会话
 * 独占锁释放、回答者摘除；**插件保持挂载、插件树与兄弟呈现位（如 Web）不受影响**。
 * 彻底停止交给进程信号——启动器的 shutdown hook 级联 dispose 调用 {@link #stop}。</p>
 *
 * <p>会话语义沿 M10-03：启动续接最新会话（独占锁），被占则明确提示并改开新会话
 * （绝不静默分脑）；`/new` 换绑即释放旧会话锁。LLM 未配置时插件 FAILED 并给出
 * 配置示例。</p>
 *
 * <p><b>纯对话装配</b>（ADR-0019）：workspace 声明为可选依赖——boot yml 不装
 * fs 工具插件行时本插件照常启动，`/permission` 降级提示"未挂载"。</p>
 *
 * <p>线程约定：REPL 循环独占虚拟线程（cli-repl）；{@link #stop} 可从树 dispose
 * 线程并发调用（关输入流打断阻塞读 + 原子停止标志）；会话与回答者的清理在 idle
 * 收尾点单线程执行，stop 只负责打断。</p>
 *
 * <p>配置（块内字段可省）：
 * <pre>{@code config:
 *   maxIterations: 30 # 单轮对话迭代上限（省略默认 10；计划模式等探索型任务建议调高）
 *   maxParallelToolCalls: 10 # 单轮并发安全工具并行上限（省略默认 10；=1 即完全串行，排障用）
 *   pipelineTimeoutMs: 120000 # 工具执行管线缺省超时毫秒（省略默认 120s）
 *   governance: {}    # 上下文治理阈值段（省略即缺省常量）}</pre></p>
 */
public final class CliPlugin implements Plugin<JsonNode> {

    private final BufferedReader in;
    private final PrintStream out;
    /** 会话目录（null = DuoHome 缺省 agent-sessions；测试注入临时目录）。 */
    private final Path sessionsDirOverride;
    /** 注入的 LLM 执行链（null = apply 时按 LlmConfig 装配；测试注入 mock）。 */
    private final LlmAdapter llmOverride;
    /** 请求变体解析器（M21 工单 05；apply 时按附件库与 vision 构建，null = 视觉未启用）。 */
    private dev.duo.harness.attachment.RequestVariants requestVariants;

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
                InteractionService.SERVICE_NAME, SkillRegistry.SERVICE_NAME,
                CommandsRegistry.SERVICE_NAME);
    }

    /**
     * workspace 可选依赖（ADR-0019）：fs 插件缺席的纯对话装配照常启动，
     * `/permission` 降级为"未挂载"提示；fs 插件在场时照常可用。
     */
    @Override
    public Set<String> optionalInject() {
        return Set.of(WorkspacePolicy.SERVICE_NAME,
                dev.duo.harness.attachment.AttachmentStore.SERVICE_NAME);
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
        CommandsRegistry commands = ctx.as(CliCommandsView.class).commands();
        // workspace 是可选依赖（ADR-0019）：fs 插件缺席的纯对话装配照常启动——
        // hasService 判存接线，缺席即 null，/permission 走降级提示
        WorkspacePolicy workspacePolicy = ctx.hasService(WorkspacePolicy.SERVICE_NAME)
                ? ctx.as(CliWorkspaceView.class).workspace() : null;

        LlmAdapter llm = llmOverride != null ? llmOverride : loadLlm();
        // 视觉链路（M21 工单 05）：llm.vision=true 且附件库在册时构建请求变体解析器
        dev.duo.harness.attachment.AttachmentStore attachments =
                ctx.hasService(dev.duo.harness.attachment.AttachmentStore.SERVICE_NAME)
                        ? ctx.as(CliAttachmentsView.class).attachments() : null;
        // 请求变体解析器（M21 工单 05）：vision=true 且附件库在册时构建（引用 → base64 图片部件）
        this.requestVariants = attachments == null || !visionEnabled ? null
                : new dev.duo.harness.attachment.RequestVariants(attachments,
                        dev.duo.harness.core.api.boot.DuoHome.resolve().root()
                                .resolve("cache/attachments"));
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
            // 继承被占会话的权限档（BUG-20260919-03 裁定，ADR-0020 决策 10 的双开延续）：
            // 占用改开不是用户开新话题，治理态不因呈现位轮转而丢——继承并落事件（重启链延续）
            String inherited = Session.permissionModeOf(
                    sessionsDir.resolve(e.sessionId() + ".jsonl"));
            if (inherited != null && workspacePolicy != null) {
                try {
                    workspacePolicy.setMode(WorkspacePolicy.Mode.parse(inherited));
                    session.append(dev.duo.harness.session.SessionEvent.permissionMode(inherited));
                    out.println("[提示] 已继承被占会话的权限档: " + inherited);
                } catch (IllegalArgumentException ignored) {
                    // 被占会话的档位记录非法——保持缺省
                }
            }
        }

        // 执行链与 HITL 供给（呈现位共享装配器）：治理（governance 段可省——缺省常量）、
        // agent、回答者（审计桥包装）、交互工具
        ContextGovernance.Tuning governanceTuning = PresenterAssembly.parseGovernance(config);
        ContextGovernance governance = PresenterAssembly.governance(llm, governanceTuning);
        // 迭代上限（BUG-20260917-03）：config.maxIterations 可省，缺省内核常量（10）
        int maxIterations = PresenterAssembly.parseMaxIterations(config);
        // 并发度（ADR-0018）：config.maxParallelToolCalls 可省，缺省 10；=1 即完全串行
        int maxParallelToolCalls = PresenterAssembly.parseMaxParallelToolCalls(config);
        // 管线缺省超时（ADR-0018）：config.pipelineTimeoutMs 可省，缺省 120s——挂工具执行段兜底
        PresenterAssembly.mountPipelineTimeout(ctx, tools, PresenterAssembly.parsePipelineTimeoutMs(config));
        // 会话标题生成（精简版，工单 M13-06）：首条消息后异步一次，/new 换绑的新会话同源触发
        SessionTitles.attach(session, llm);
        SessionHolder holder = new SessionHolder(session);
        ChatAgent agent = PresenterAssembly.chatAgent(llm, tools, session, prompts,
                maxIterations, maxParallelToolCalls, governance, ChatAgent.PRESENTER_CLI,
                requestVariants, visionEnabled);
        answererRegistration = answers.register(ctx,
                new AuditingAnswerer(holder::current, new ConsoleAnswerer(in, out)));
        PlanHolder plan = new PlanHolder();
        PresenterAssembly.registerInteractionTools(ctx, tools, answers, ChatAgent.PRESENTER_CLI,
                holder::current, () -> {
            plan.active = false;
            disposeGuidance(plan);
        });
        // todo 分解抓手（ADR-0018）：呈现状态工具随装配注册（与交互工具同供给模式）
        PresenterAssembly.registerTodoWriteTool(ctx, tools, holder::current);
        // @file 指南注入（M21 工单 07）：read 在册才注册，双呈现位同源去重——
        // CLI 无补全 UI（一期文本直打），指南照常注入
        PresenterAssembly.registerFileMentionGuide(ctx, tools, prompts);
        // /export（M21 工单 09，ADR-0022 决策 9）：双面命令随装配注册（查重先到先得
        // ——Web 已注册则跳过），CLI 写盘 cwd
        PresenterAssembly.registerExportCommand(ctx, commands, holder::current);
        // subagent 宿主发布（M15，ADR-0015）：发布父侧执行链构件——SubagentPlugin
        // 在场且配置了模板时自行装配五件工具；未配置部署零感知（只发服务，零工具）
        PresenterAssembly.publishSubagentHost(ctx, llm, governanceTuning, holder::current);
        attachSubagentTrace(session);

        // agent 引用经持取器（/new 换绑即换 agent 实例）与单飞标志（busySafe 分级的探针）
        AgentHolder agentHolder = new AgentHolder(agent);
        AtomicBoolean agentBusy = new AtomicBoolean(false);
        registerCommands(ctx, commands, llm, tools, prompts, governance, maxIterations,
                maxParallelToolCalls, sessionsDir(), holder, agentHolder, plan,
                workspacePolicy);
        // 权限档持久化（M19，ADR-0020 决策 10）：启动续接只恢复不重置——双开下另一
        // 呈现位可能刚恢复过档位，占用被迫改开的新会话不得覆盖它（BUG-20260919-03）
        PresenterAssembly.restorePermissionMode(
                ctx, session, false);

        // 续接计划模式：激活态随会话恢复（指导片段重新挂上）
        plan.active = PlanMode.isActive(session);
        if (plan.active) {
            plan.guidance = prompts.register(ctx, new PromptFragment("plan:guidance", PlanMode.GUIDANCE));
            out.println("（续接会话：当前处于计划模式，/plan off 可退出）");
        }
        out.println("会话 " + session.id() + "（工具循环上下文）。/exit 退出，/new 开新话题。");
        out.flush();

        replThread = Thread.ofVirtual().name("cli-repl").start(() ->
                replLoop(commands, skills, holder, plan, agentHolder, agentBusy));
        return this::stop;
    }

    /** 会话目录：注入优先，缺省 DuoHome 的 agent-sessions（apply 与 /new 共用）。 */
    private Path sessionsDir() {
        return sessionsDirOverride != null
                ? sessionsDirOverride : DuoHome.resolve().resolveDir("agent-sessions");
    }

    /**
     * 未完成子任务的成果摘要摘取（终端可用性优先）：工具调用清单取前
     * {@value #SUMMARY_HEAD_LINES} 行、末次结果摘录取前 {@value #SUMMARY_TAIL_LINES} 行，
     * 其余折叠为指引——终端的可用性优先，完整内容可从子会话文件与父会话日志读取。
     */
    private void printAbbreviatedSummary(String text) {
        int newline = text.indexOf('\n');
        String body = newline < 0 ? "" : text.substring(newline + 1);
        if (body.isBlank()) {
            return;
        }
        String[] lines = body.split("\n");
        int head = Math.min(lines.length, SUMMARY_HEAD_LINES);
        for (int i = 0; i < head; i++) {
            out.println("           " + lines[i]);
        }
        if (lines.length > head) {
            out.println("           …（工具调用清单略，共 " + lines.length + " 行；完整摘要见子会话文件）");
        }
        int excerpt = body.indexOf(dev.duo.harness.agent.subagent.SubagentManager.EXCERPT_MARKER);
        if (excerpt >= 0) {
            String[] tailLines = body.substring(excerpt).split("\n");
            int tail = Math.min(tailLines.length, SUMMARY_TAIL_LINES);
            for (int i = 1; i < tail; i++) {
                out.println("           " + tailLines[i]);
            }
        }
    }

    /** 未完成摘要的终端呈现行数上限（工具调用清单头部 / 末次结果摘录）。 */
    private static final int SUMMARY_HEAD_LINES = 6;
    private static final int SUMMARY_TAIL_LINES = 4;

    /**
     * 子任务过程行（M15 工单 05）：会话监听器打印 spawned/completed 状态——
     * spawned 在 spawn 工具执行时落会话（[子任务] 已派生），completed 由子代理
     * 后台线程回流（[子任务] 完成 + 最终回答；跨线程打印经 PrintStream 同步）。
     * /new 换绑后对新会话重新挂载（旧监听器随旧会话 close 自动失效）。
     */
    private void attachSubagentTrace(Session target) {
        target.addListener((index, event) -> {
            switch (event.type()) {
                case dev.duo.harness.session.SessionEvent.SUBAGENT_SPAWNED ->
                        out.println("  [子任务] 已派生子代理 " + event.toolCallId()
                                + "（模板 " + event.toolName() + "），后台运行中");
                case dev.duo.harness.session.SessionEvent.SUBAGENT_COMPLETED -> {
                    String text = event.text();
                    out.println("  [子任务] " + text.split("\n", 2)[0]);
                    int idx = text.indexOf(dev.duo.harness.agent.subagent.SubagentManager.FINAL_ANSWER_MARKER);
                    if (idx >= 0) {
                        // 完成路径：结论即要点
                        out.println("           " + text.substring(
                            idx + dev.duo.harness.agent.subagent.SubagentManager.FINAL_ANSWER_MARKER.length()).strip());
                    } else {
                        // 未完成路径（迭代上限/失败）：中间成果摘要取前几条 + 末次结果摘录，
                        // 完整摘要留在父会话事件与子会话文件里（摘要可达数十行，终端不刷屏）
                        printAbbreviatedSummary(text);
                    }
                }
                default -> {
                    // 其余事件不经 CLI 过程行（对话流/工具卡由 agent 循环回调呈现）
                }
            }
            out.flush();
        });
    }

    /**
     * 斜杠命令注册（M19，ADR-0020 决策 1）：CLI 命令从 REPL 硬编码迁移为注册表调用——
     * /exit、/new、/plan 为 CLI 适用面（handler 闭包本呈现位的会话与计划态）；/permission
     * 双面 ANY + busySafe（volatile 治理态读写，浏览器与终端都可切档）。另有 /compact、
     * /title 双面命令经共享装配器注册（见下方 registerCompactCommand/registerTitleCommand）。
     * 命令随注册方作用域自动摘除。
     */
    private void registerCommands(Context ctx, CommandsRegistry commands, LlmAdapter llm,
                                  ToolsService tools, PromptRegistry prompts,
                                  ContextGovernance governance, int maxIterations,
                                  int maxParallelToolCalls, Path sessionsDir,
                                  SessionHolder holder, AgentHolder agentHolder,
                                  PlanHolder plan, WorkspacePolicy workspacePolicy) {
        commands.register(ctx, new CommandDefinition("exit", "结束终端对话（会话锁释放，插件保持挂载）",
                CommandScope.CLI, false, context -> {
                context.requestEnd();
                return "";
            }));
        commands.register(ctx, new CommandDefinition("new", "换绑新会话（旧会话锁释放，标题生成与子任务过程行重挂）",
                CommandScope.CLI, false, context -> {
                Session previous = holder.session;
                holder.session = Session.create(sessionsDir);
                agentHolder.agent = PresenterAssembly.chatAgent(llm, tools, holder.session, prompts,
                        maxIterations, maxParallelToolCalls, governance, ChatAgent.PRESENTER_CLI,
                        requestVariants, visionEnabled);
                SessionTitles.attach(holder.session, llm);
                attachSubagentTrace(holder.session); // 子任务过程行随换绑重挂（旧监听随 close 失效）
                previous.close(); // 换绑即释放旧会话独占锁（本进程不再使用它）
                // 用户显式开新话题：无切档记录即重置回 yml 缺省（ADR-0020 决策 10）
                PresenterAssembly.restorePermissionMode(
                        ctx, holder.session, true);
                plan.active = false;
                disposeGuidance(plan);
                return "新会话 " + holder.session.id() + "。";
            }));
        // /permission 双面可用（ANY）：handler 只依赖 fs 插件的全局 workspace 服务
        // （无呈现位归属，切档即全局生效）——M19 用户故事 1（浏览器直接切档）；
        // 其余三命令闭包本呈现位状态（holder/plan/agent），维持 CLI 面
        commands.register(ctx, new CommandDefinition("permission",
                "查看或切换权限预设：/permission [read-only|workspace-write|danger-full-access]",
                CommandScope.ANY, true, context -> {
                if (workspacePolicy == null) {
                    return "workspace 服务未挂载（未装配 fs 工具插件），/permission 不可用。";
                }
                if (context.args().isEmpty()) {
                    return "当前预设: " + workspacePolicy.mode().configName()
                            + "（可选: read-only / workspace-write / danger-full-access）";
                }
                try {
                    workspacePolicy.setMode(WorkspacePolicy.Mode.parse(context.args()));
                    // 档位跟对话走（M19，ADR-0020 决策 10）：切档落会话事件——重开恢复
                    context.session().append(
                            dev.duo.harness.session.SessionEvent.permissionMode(
                                    workspacePolicy.mode().configName()));
                    return "已切换: " + workspacePolicy.mode().configName();
                } catch (IllegalArgumentException e) {
                    return e.getMessage();
                }
            }));
        // /compact 与 /title（M19）：双面命令经共享装配器注册（查重先到先得——Web 侧
        // 同款），会话取发起方当前值
        PresenterAssembly.registerCompactCommand(
                ctx, commands, governance);
        PresenterAssembly.registerTitleCommand(ctx, commands);
        commands.register(ctx, new CommandDefinition("plan",
                "计划模式：/plan 进入（可携任务描述直接推进）、/plan off 退出",
                CommandScope.CLI, false, context -> {
                String rest = context.args();
                if (rest.equals("off")) {
                    if (plan.active) {
                        holder.current().append(PlanMode.exitedEvent());
                        disposeGuidance(plan);
                        plan.active = false;
                        return "已退出计划模式。";
                    }
                    return "当前不在计划模式。";
                }
                String notice;
                if (plan.active) {
                    notice = "已在计划模式中。";
                } else {
                    holder.current().append(PlanMode.enteredEvent());
                    plan.active = true;
                    plan.guidance = prompts.register(ctx, new PromptFragment("plan:guidance", PlanMode.GUIDANCE));
                    notice = "已进入计划模式（先探索与设计，完成后调 exit_plan_mode 呈交计划；/plan off 退出）。";
                }
                if (!rest.isEmpty()) {
                    context.forward(rest);
                }
                return notice;
            }));
    }

    /**
     * REPL 主循环（M19 起输入解释走命令注册表共享入口）：读行 → dispatch（命令 →
     * 技能直调 → 未知命令报错）→ 命令即回显结果（/exit 经结束回调跳出），透传文本
     * 交 agent 执行（单飞标志随执行期置位——busySafe 分级的探针）。
     */
    private void replLoop(CommandsRegistry commands, SkillRegistry skills, SessionHolder holder,
                          PlanHolder plan, AgentHolder agentHolder, AtomicBoolean agentBusy) {
        AtomicBoolean endRequested = new AtomicBoolean(false);
        try {
            while (!stopped.get()) {
                out.print("你> ");
                out.flush();
                String line = in.readLine();
                if (line == null) {
                    break;
                }
                String input = line.strip();
                if (input.isEmpty()) {
                    continue;
                }
                CommandOutcome outcome = commands.dispatch(input,
                        new CommandEnv(CommandScope.CLI, holder::current, out::println,
                                () -> endRequested.set(true), agentBusy::get),
                        skills);
                if (endRequested.get()) {
                    break; // /exit：请求结束回调已置位（done 审计已落盘）
                }
                String userText;
                if (outcome.isCommand()) {
                    if (!outcome.text().isEmpty()) {
                        out.println(outcome.text());
                        out.flush();
                    }
                    if (outcome.forward() == null) {
                        continue;
                    }
                    userText = outcome.forward(); // /plan 携任务描述：回显结果后再推进
                } else {
                    userText = outcome.text();
                }
                agentBusy.set(true);
                try {
                    var reply = agentHolder.agent.send(userText, new AgentListener() {
                        @Override
                        public void onChunk(String text) {
                            out.print(text);
                            out.flush();
                        }

                        @Override
                        public void onToolCall(String toolName, String argumentsJson) {
                            if (TodoWriteTool.NAME.equals(toolName)) {
                                // 清单更新的参数是整表 JSON（终端不画清单）——只打动作行，
                                // 计数摘要随 onToolResult 的结果文本给出（ADR-0018）
                                out.println();
                                out.println("  [清单] 更新任务清单…");
                                out.flush();
                                return;
                            }
                            out.println();
                            out.println("  [调工具] " + toolName + " " + argumentsJson);
                            out.flush();
                        }

                        @Override
                        public void onToolResult(String toolName, String resultText, boolean isError) {
                            if (TodoWriteTool.NAME.equals(toolName)) {
                                out.println("  [清单] " + resultText);
                                out.flush();
                                return;
                            }
                            out.println("  [工具" + (isError ? "错误] " : "结果] ") + resultText);
                            out.flush();
                        }
                    });
                    if (!reply.completed()) {
                        out.println("  [异常终止] " + reply.finalText());
                    }
                } catch (PluginException e) {
                    out.println("  [错误] " + e.getMessage());
                } finally {
                    agentBusy.set(false);
                }
                out.println();
                out.flush();
            }
        } catch (IOException e) {
            // stop() 关闭输入流打断阻塞读——按 /exit 同语义收尾（idle）
        }
        goIdle(holder, plan);
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

    /** 视觉能力开关（llm.vision，M21 工单 05；loadLlm 时刷新）。 */
    private static volatile boolean visionEnabled;

    /** LLM 装配：配置缺失时 FAILED 并给出示例（沿 CLI 既有提示形态）。 */
    private static LlmAdapter loadLlm() {
        try {
            dev.duo.harness.llm.LlmConfig cfg = dev.duo.harness.llm.LlmConfig.load();
            visionEnabled = cfg.vision();
            return PresenterAssembly.llmAdapter(cfg);
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

    /** 可变引用：/new 换绑即换 agent 实例（旧 agent 随旧会话弃用）。 */
    private static final class AgentHolder {
        ChatAgent agent;

        AgentHolder(ChatAgent agent) {
            this.agent = agent;
        }
    }

    /** 计划模式装配态：激活标志 + 指导片段的注销器（批准/退出时摘除）。 */
    private static final class PlanHolder {
        boolean active;
        Disposable guidance;
    }

    /**
     * 技能直调识别已上移命令注册表共享入口（M19，ADR-0020 决策 3）：
     * {@link CommandsRegistry#dispatch} 按"命令 → 技能直调 → 未知命令报错"解释输入，
     * 两呈现位同款；本类不再自带技能解析。
     */

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

    /** 命令注册表的视图接口（方法名即服务名 "commands"）。 */
    interface CliCommandsView {

        CommandsRegistry commands();
    }

    /** attachments 服务的视图接口（方法名即服务名 "attachments"）。 */
    interface CliAttachmentsView {

        dev.duo.harness.attachment.AttachmentStore attachments();
    }

    /** workspace 服务的视图接口（方法名即服务名 "workspace"）。 */
    interface CliWorkspaceView {

        WorkspacePolicy workspace();
    }
}
