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
import java.util.List;
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
 * <p><b>事件驱动主循环</b>（M23 工单 01，ADR-0025 决策一）：REPL 从"阻塞 readLine +
 * 同步执行"改为读者线程与 turn 线程拆分——读者线程常驻读 stdin：空闲期走命令分发
 * 与 turn 启动；busy 期普通文本注入收件箱 next-step 级（插队，提示行回显「已插队」）、
 * 斜杠命令照走注册表（busySafe 即行，非 busySafe 得到等待回应）；审批/提问/计划的
 * 应答行经应答闸门优先路由给 {@link ConsoleAnswerer}。turn 线程执行 send，收口后
 * 消费收件箱 next-turn 级（多条合并为一条，立即开新轮）。执行期输入不再沉入行缓冲
 * 被静默当新输入消费。</p>
 *
 * <p>线程约定：读者线程独占虚拟线程（cli-repl）；执行期 turn 跑在独立虚拟线程
 * （cli-agent）；{@link #stop} 可从树 dispose 线程并发调用（关输入流打断阻塞读 +
 * 打断两个线程 + 应答闸门 fail-closed）；会话与回答者的清理在 idle 收尾点单线程
 * 执行（读者线程 join turn 线程后统一收尾），stop 只负责打断。busy 期 busySafe
 * 命令在读者线程落会话事件，与 turn 线程的对话事件并发——同 Web 面 busySafe
 * 命令的既有同况，数据安全由 Session.append 内部锁保证（「单写者」约定按呈现位
 * 主对话流口径理解，审查已对账）。</p>
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
    /** 当前 LLM 配置（loadLlm 刷新；/model 白名单与当前模型来源，M24 工单 09）。 */
    private volatile dev.duo.harness.llm.LlmConfig activeConfig;
    /** 可换执行链（apply 构建；/model 的 swap 入口；注入 mock 模式为 null）。 */
    private volatile dev.duo.harness.llm.SwappableLlmAdapter swappableLlm;
    /** 请求变体解析器（M21 工单 05；apply 时按附件库与 vision 构建，null = 视觉未启用）。 */
    private dev.duo.harness.attachment.RequestVariants requestVariants;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private Thread replThread;
    /** 执行中的 turn 线程（cli-agent；stop 打断与读者线程 EOF 等待的锚点）。 */
    private volatile Thread turnThread;
    /**
     * 应答闸门（M23 工单 01）：审批/提问/计划的输入行通道——读者线程见 pending
     * 即把行路由进来，ConsoleAnswerer 经 {@code awaitLine()} 取行；EOF/stop 置
     * closed 后取行立即 fail-closed（不依赖在飞时序补行，审查修复）。
     */
    private final AnswerGate answerGate = new AnswerGate();
    /** 回答者注册的注销器（idle 与 stop 均摘除；幂等守卫见 #detachAnswerer）。 */
    private Disposable answererRegistration;
    private volatile boolean answererDetached;
    /** 终端回答者实例（M23 工单 03：turn 边界审批计数归零的直连句柄）。 */
    private volatile ConsoleAnswerer consoleAnswerer;
    /** turn 收口/停止标志（M23 工单 04：通知路由的 idle 开轮需要跨方法可达）。 */
    private final AtomicBoolean endRequested = new AtomicBoolean(false);
    /** 后台任务注册表（M23 工单 04/06；null = fs 工具未装——提示行无后台段）。 */
    private volatile dev.duo.harness.tools.fs.BackgroundTaskRegistry backgroundTasks;

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
                dev.duo.harness.attachment.AttachmentStore.SERVICE_NAME,
                dev.duo.harness.tools.fs.BackgroundTaskRegistry.SERVICE_NAME,
                dev.duo.harness.tools.fs.PermissionRules.SERVICE_NAME,
                dev.duo.harness.tools.ConnectorStatusBoard.SERVICE_NAME,
                dev.duo.harness.agent.memory.MemoryBook.SERVICE_NAME);
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
        // 记忆本可选依赖（M25 工单 02）：memory 行缺席零感降级（零注入）
        dev.duo.harness.agent.memory.MemoryBook memory =
                ctx.hasService(dev.duo.harness.agent.memory.MemoryBook.SERVICE_NAME)
                        ? ctx.as(CliMemoryView.class).memory() : null;

        LlmAdapter llm = llmOverride != null ? llmOverride : loadLlm();
        if (llm instanceof dev.duo.harness.llm.SwappableLlmAdapter swappable) {
            this.swappableLlm = swappable; // /model 切换的 swap 入口（M24 工单 09）
        }
        // 视觉链路（M21 工单 05）：llm.vision=true 且附件库在册时构建请求变体解析器
        dev.duo.harness.attachment.AttachmentStore attachments =
                ctx.hasService(dev.duo.harness.attachment.AttachmentStore.SERVICE_NAME)
                        ? ctx.as(CliAttachmentsView.class).attachments() : null;
        // 请求变体解析器（M21 工单 05）：vision=true 且附件库在册时构建（引用 → base64 图片部件）
        this.requestVariants = attachments == null || !visionEnabled ? null
                : new dev.duo.harness.attachment.RequestVariants(attachments,
                        dev.duo.harness.core.api.boot.DuoHome.resolve().root()
                                .resolve("cache/attachments"));
        // files 投递（M21 工单 06）：vision 且 imageDelivery=files 时变体上传换 file_id
        this.fileDelivery = attachments == null || !visionEnabled || !filesDeliveryEnabled
                ? null
                : new dev.duo.harness.attachment.ImageFileDelivery(
                        new dev.duo.harness.attachment.FilesApiUploader(
                                deliveryBaseUrl, deliveryApiKey, java.time.Duration.ofSeconds(60)),
                        dev.duo.harness.core.api.boot.DuoHome.resolve().root()
                                .resolve("cache/attachments/files-index.json"));
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
                requestVariants, visionEnabled, fileDelivery, planBashDetector(workspacePolicy),
                memory);
        ConsoleAnswerer console = new ConsoleAnswerer(answerGate::awaitLine, out, resolvePermissionRules(ctx));
        this.consoleAnswerer = console;
        answererRegistration = answers.register(ctx,
                new AuditingAnswerer(holder::current, wrapRuleGenerating(ctx, console, holder::current)));
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
        // read_image 视觉闸门回填（M21 收口修正）：loadLlm 已刷新 visionEnabled
        PresenterAssembly.wireReadImageVisionGate(tools, visionEnabled);
        // /export（M21 工单 09，ADR-0022 决策 9）：双面命令随装配注册（查重先到先得
        // ——Web 已注册则跳过），CLI 写盘 cwd
        PresenterAssembly.registerExportCommand(ctx, commands);
        // subagent 宿主发布（M15，ADR-0015）：发布父侧执行链构件——SubagentPlugin
        // 在场且配置了模板时自行装配五件工具；未配置部署零感知（只发服务，零工具）
        PresenterAssembly.publishSubagentHost(ctx, llm, governanceTuning, holder::current);
        attachSubagentTrace(session);

        // agent 引用经持取器（/new 换绑即换 agent 实例）与单飞标志（busySafe 分级的探针）
        AgentHolder agentHolder = new AgentHolder(agent);
        AtomicBoolean agentBusy = new AtomicBoolean(false);
        // 协作式中断的再按闸（M23 工单 02）：运行期第一次 Ctrl+C 请求中断，第二次强制退出
        AtomicBoolean interruptArmed = new AtomicBoolean(false);
        // 后台任务完成通知路由（M23 工单 04，ADR-0025 决策二）：agent 空闲 → 直接开新
        // turn 消费通知（first-wins 必达）；busy → 挂收件箱 next-turn（turn 收口合并消费）。
        // 注册表缺席（fs 工具未装）即无通知，零感
        if (ctx.hasService(dev.duo.harness.tools.fs.BackgroundTaskRegistry.SERVICE_NAME)) {
            var registry = ctx.as(BackgroundTasksView.class).backgroundTasks();
            this.backgroundTasks = registry;
            registry.addListener(task -> {
                // 归属过滤（M23 工单 06 验收修正）：CLI 只消费本位发起（或无归属）的任务，
                // Web 侧任务完成不在终端开轮/注入
                if (task.owner() != null && !dev.duo.harness.agent.ChatAgent.PRESENTER_CLI.equals(task.owner())) {
                    return;
                }
                String notice = task.notice();
                if (agentBusy.compareAndSet(false, true)) {
                    startTurn(notice, agentHolder, agentBusy, interruptArmed); // 空闲：开新轮消费
                } else {
                    // busy：挂 next-turn 收口合并消费。已知边界（审查记档）：与 turn 收口
                    // 竞态窗口内注入的通知延至下次交互消费——不丢（必达=延迟语义）
                    agentHolder.agent.injectNextTurn(notice);
                }
            });
        }
        // MCP 重连耗尽通知（M24 工单 05）：连接器状态板订阅——通知经收件箱注入（模型+用户可见）；
        // 状态板服务缺席（未挂 mcp 行）零感；mcp 行须先于 cli 行装配（订阅在 apply 时判定，
        // optionalInject 声明的时序契约）
        if (ctx.hasService(dev.duo.harness.tools.ConnectorStatusBoard.SERVICE_NAME)) {
            dev.duo.harness.tools.ConnectorStatusBoard board =
                    ctx.as(ConnectorStatusView.class).connectorStatus();
            board.onGaveUp(notice -> {
                if (agentBusy.compareAndSet(false, true)) {
                    startTurn(notice, agentHolder, agentBusy, interruptArmed); // 空闲：开新轮消费
                } else {
                    agentHolder.agent.injectNextTurn(notice);
                }
            });
        }
        registerCommands(ctx, commands, llm, tools, prompts, governance, maxIterations,
                maxParallelToolCalls, sessionsDir(), holder, agentHolder, plan,
                workspacePolicy, agentBusy, interruptArmed, memory);
        // 权限档持久化（M19，ADR-0020 决策 10）：启动续接只恢复不重置——双开下另一
        // 呈现位可能刚恢复过档位，占用被迫改开的新会话不得覆盖它（BUG-20260919-03）
        PresenterAssembly.restorePermissionMode(
                ctx, session, false);
        // 会话级权限规则恢复（M24，ADR-0026 决策一）：续接该会话的规则快照
        PresenterAssembly.restorePermissionRules(ctx, session);
        // 模型意图提示（M24 工单 09，ADR-0026 决策六）：resume 续接时意图 ≠ 当前绑定
        // 则横幅提示、不自动切——保存意图与执行绑定分离，切换由用户拍板
        String modelIntent = session.modelIntent();
        if (modelIntent != null && activeConfig != null && !modelIntent.equals(activeConfig.model())) {
            String suggestion = activeConfig.modelAllowed(modelIntent)
                    ? "；/model " + modelIntent + " 切回"
                    : "（该模型不在当前 llm.models 白名单，如需切回请先在 config.yml 声明）";
            out.println("（该会话上次使用模型 " + modelIntent + "，当前 " + activeConfig.model() + suggestion + "）");
        }

        // 续接计划模式：激活态随会话恢复（指导片段重新挂上）
        plan.active = PlanMode.isActive(session);
        if (plan.active) {
            plan.guidance = prompts.register(ctx, new PromptFragment("plan:guidance", PlanMode.GUIDANCE));
            out.println("（续接会话：当前处于计划模式，/plan off 可退出）");
        }
        out.println("会话 " + session.id() + "（工具循环上下文）。/exit 退出，/new 开新话题。");
        out.flush();

        replThread = Thread.ofVirtual().name("cli-repl").start(() ->
                replLoop(commands, skills, holder, plan, agentHolder, agentBusy, interruptArmed));
        registerSigintHandler(agentHolder, agentBusy, interruptArmed);
        return this::stop;
    }

    /** 会话目录：注入优先，缺省 DuoHome 的 agent-sessions（apply 与 /new 共用）。 */
    private Path sessionsDir() {
        return sessionsDirOverride != null
                ? sessionsDirOverride : DuoHome.resolve().resolveDir("agent-sessions");
    }

    /**
     * plan 态 bash 只读判定器（M24 工单 04，ADR-0026 决策三）：workspace 在场时按
     * 其根构建（与 WorkspaceApprovalPlugin 裁决链的 detector 同源同参——判定器只读
     * 无状态，双实例无冲突）；缺席（纯对话装配无 bash）为 null，plan 态 bash 到达
     * 一律 fail-closed 拒。
     */
    private static dev.duo.harness.tools.fs.ReadOnlyBashDetector planBashDetector(
            WorkspacePolicy workspacePolicy) {
        return workspacePolicy == null
                ? null : new dev.duo.harness.tools.fs.ReadOnlyBashDetector(workspacePolicy.root());
    }

    /**
     * /permission rules 子命令（M24，ADR-0026 决策一）：list（缺省）两级规则清单、
     * rm &lt;P#|S#&gt; 删除——项目级重写 .duo/settings.json（写入失败降级提示不静默）、
     * 会话级更新运行时态并落 permission/rules 事件快照（latest-wins 投影、resume 恢复）。
     * 规则服务缺席（未挂 permission-rules 插件）降级提示。
     */
    private static String permissionRulesCommand(Context ctx, CommandContext context, String rest) {
        dev.duo.harness.tools.fs.PermissionRules rules = resolvePermissionRules(ctx);
        if (rules == null) {
            return "permission-rules 服务未挂载，权限规则功能不可用（agent-demo.yml 增挂 "
                    + "PermissionRulesPlugin）。";
        }
        if (rest.isEmpty() || rest.equals("list")) {
            return renderRules(rules);
        }
        if (!rest.startsWith("rm")) {
            return "未知 rules 子命令: " + rest + "（可用: list / rm <P#|S#>）";
        }
        String target = rest.substring(2).strip().toUpperCase();
        if (target.length() < 2 || (target.charAt(0) != 'P' && target.charAt(0) != 'S')) {
            return "用法: /permission rules rm <P#|S#>（P=项目级 S=会话级）";
        }
        int index;
        try {
            index = Integer.parseInt(target.substring(1));
        } catch (NumberFormatException e) {
            return "规则编号须为数字: " + target;
        }
        try {
            if (target.charAt(0) == 'P') {
                rules.removeProjectRule(index);
                return "已删除项目级规则 P" + index + "（.duo/settings.json 已重写）。";
            }
            var updated = rules.removeSessionRule(index);
            context.session().append(dev.duo.harness.session.SessionEvent.permissionRules(
                    dev.duo.harness.tools.fs.PermissionRules.rulesToJson(updated)));
            return "已删除会话级规则 S" + index + "。";
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
            return "删除失败: " + e.getMessage();
        }
    }

    /** 权限规则服务可选解析：缺席（未挂 permission-rules 插件）或解析失败返回 null。 */
    private static dev.duo.harness.tools.fs.PermissionRules resolvePermissionRules(Context ctx) {
        try {
            return ctx.hasService(dev.duo.harness.tools.fs.PermissionRules.SERVICE_NAME)
                    ? ctx.as(CliPermissionRulesView.class).permissionRules() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 「总是允许」规则生成包装（M24 工单 02，ADR-0026 决策一）：规则服务缺席原样
     * 返回；在场时拦截 allowAlways 答案生成规则，会话级经 sink 落当前会话事件。
     */
    private static dev.duo.harness.tools.Answerer wrapRuleGenerating(Context ctx, dev.duo.harness.tools.Answerer delegate,
                                               java.util.function.Supplier<Session> session) {
        dev.duo.harness.tools.fs.PermissionRules rules = resolvePermissionRules(ctx);
        if (rules == null) {
            return delegate;
        }
        return new dev.duo.harness.tools.fs.RuleGeneratingAnswerer(delegate, rules,
                json -> session.get().append(dev.duo.harness.session.SessionEvent.permissionRules(json)));
    }

    /** 两级规则清单渲染（P/S 编号与 rm 目标一一对应）。 */
    private static String renderRules(dev.duo.harness.tools.fs.PermissionRules rules) {
        StringBuilder sb = new StringBuilder("项目级（.duo/settings.json）：");
        appendRules(sb, 'P', rules.projectRules());
        sb.append("\n会话级：");
        appendRules(sb, 'S', rules.sessionRules());
        return sb.toString();
    }

    private static void appendRules(StringBuilder sb, char letter,
                                    java.util.List<dev.duo.harness.tools.fs.PermissionRules.Rule> rules) {
        if (rules.isEmpty()) {
            sb.append("（无）");
            return;
        }
        for (int i = 0; i < rules.size(); i++) {
            var rule = rules.get(i);
            sb.append(String.format("%n  %c%d  %-5s  %-9s  %s", letter, i + 1,
                    rule.decision().name().toLowerCase(), rule.tool(),
                    rule.prefix() == null ? "(工具级)" : rule.prefix() + "*"));
        }
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
                                  PlanHolder plan, WorkspacePolicy workspacePolicy,
                                  AtomicBoolean agentBusy, AtomicBoolean interruptArmed,
                                  dev.duo.harness.agent.memory.MemoryBook memory) {
        commands.register(ctx, new CommandDefinition("exit", "结束终端对话（会话锁释放，插件保持挂载）",
                CommandScope.CLI, false, context -> {
                context.requestEnd();
                return "";
            }));
        // /stop（M23 工单 02，ADR-0025 决策一）：SIGINT 不可拦截环境的兜底暂停入口，
        // busySafe——运行中即执行；与 Ctrl+C 单击同语义（协作式中断，会话停可恢复态）
        commands.register(ctx, new CommandDefinition("stop",
                "协作式中断当前任务（已流出内容保留，会话停在可恢复态；再发消息即续接）",
                CommandScope.CLI, true, context -> {
                if (!agentBusy.get() || !agentHolder.agent.requestInterrupt()) {
                    return "当前无执行中任务，无须中断。";
                }
                return "已请求中断当前任务（协作式收口中，稍候）…";
            }));
        commands.register(ctx, new CommandDefinition("new", "换绑新会话（旧会话锁释放，标题生成与子任务过程行重挂）",
                CommandScope.CLI, false, context -> {
                Session previous = holder.session;
                holder.session = Session.create(sessionsDir);
                agentHolder.agent = PresenterAssembly.chatAgent(llm, tools, holder.session, prompts,
                        maxIterations, maxParallelToolCalls, governance, ChatAgent.PRESENTER_CLI,
                        requestVariants, visionEnabled, fileDelivery, planBashDetector(workspacePolicy),
                        memory);
                SessionTitles.attach(holder.session, llm);
                attachSubagentTrace(holder.session); // 子任务过程行随换绑重挂（旧监听随 close 失效）
                previous.close(); // 换绑即释放旧会话独占锁（本进程不再使用它）
                // 用户显式开新话题：无切档记录即重置回 yml 缺省（ADR-0020 决策 10）
                PresenterAssembly.restorePermissionMode(
                        ctx, holder.session, true);
                // 会话级规则随会话生命周期（ADR-0026 决策一）：新会话无规则事件即清空
                PresenterAssembly.restorePermissionRules(ctx, holder.session);
                plan.active = false;
                disposeGuidance(plan);
                return "新会话 " + holder.session.id() + "。";
            }));
        // /model（M24 工单 09，ADR-0026 决策六）：白名单内运行时切模型——切换落
        // model/intent 会话事件（保存意图）、swap 换链下一 turn 生效；清单外拒切
        // （模型名决定成本面）；清单缺席 = 不可切
        commands.register(ctx, new CommandDefinition("model",
                "查看或切换模型：/model [模型名]（可切清单 = config.yml llm.models 白名单）",
                CommandScope.CLI, true, context -> {
                if (activeConfig == null || swappableLlm == null) {
                    return "当前装配不支持运行时切模型（LLM 执行链为注入 mock）。";
                }
                var models = activeConfig.models();
                if (models.isEmpty()) {
                    return "/model 不可切：config.yml 的 llm 段未声明 models 白名单。";
                }
                String target = context.args().strip();
                if (target.isEmpty()) {
                    StringBuilder sb = new StringBuilder("当前模型: ").append(activeConfig.model())
                            .append("\n可切清单:");
                    for (String m : models) {
                        sb.append("\n  - ").append(m)
                                .append(m.equals(activeConfig.model()) ? "（当前）" : "");
                    }
                    return sb.toString();
                }
                if (!activeConfig.modelAllowed(target)) {
                    return "模型不在白名单: " + target + "（可切: " + String.join(", ", models) + "）";
                }
                if (target.equals(activeConfig.model())) {
                    return "已是当前模型: " + target;
                }
                dev.duo.harness.llm.LlmConfig next = activeConfig.withModel(target);
                swappableLlm.swap(PresenterAssembly.llmAdapter(next));
                activeConfig = next;
                context.session().append(dev.duo.harness.session.SessionEvent.modelIntent(target));
                return "已切换: " + target + "（下一轮对话生效）";
            }));
        // /effort（M24 工单 10，ADR-0026 决策六）：思考等级四档归一——切换落
        // model/effort 会话事件、swap 换链下一 turn 生效；映射按 provider 四行走
        // （anthropic thinking+budget / openai-compat reasoning_effort / glm 开关 /
        // deepseek 显式降级标注），不支持不静默；无参显示当前档
        commands.register(ctx, new CommandDefinition("effort",
                "查看或切换思考等级：/effort [off|low|medium|high]（缺省 medium，下一轮对话生效）",
                CommandScope.CLI, true, context -> {
                if (activeConfig == null || swappableLlm == null) {
                    return "当前装配不支持运行时切思考等级（LLM 执行链为注入 mock）。";
                }
                String target = context.args().strip().toLowerCase(java.util.Locale.ROOT);
                if (target.isEmpty()) {
                    StringBuilder sb = new StringBuilder("当前思考等级: ").append(activeConfig.effort())
                            .append("\n可切档位:");
                    for (String level : dev.duo.harness.llm.LlmConfig.EFFORT_LEVELS) {
                        sb.append("\n  - ").append(level)
                                .append(level.equals(activeConfig.effort()) ? "（当前）" : "");
                    }
                    sb.append("\n映射: ").append(dev.duo.harness.llm.LlmConfig
                            .effortNote(activeConfig.provider()));
                    return sb.toString();
                }
                if (!dev.duo.harness.llm.LlmConfig.effortAllowed(target)) {
                    return "非法档位: " + target + "（可切: "
                            + String.join(", ", dev.duo.harness.llm.LlmConfig.EFFORT_LEVELS) + "）";
                }
                if (target.equals(activeConfig.effort())) {
                    return "已是当前档位: " + target;
                }
                dev.duo.harness.llm.LlmConfig next = activeConfig.withEffort(target);
                swappableLlm.swap(PresenterAssembly.llmAdapter(next));
                activeConfig = next;
                context.session().append(dev.duo.harness.session.SessionEvent.modelEffort(target));
                return "已切换: " + target + "（下一轮对话生效）\n"
                        + dev.duo.harness.llm.LlmConfig.effortNote(next.provider());
            }));
        // /permission 双面可用（ANY）：handler 只依赖 fs 插件的全局 workspace 服务
        // （无呈现位归属，切档即全局生效）——M19 用户故事 1（浏览器直接切档）；
        // 其余三命令闭包本呈现位状态（holder/plan/agent），维持 CLI 面。
        // rules 子命令（M24，ADR-0026 决策一）：两级规则清单与删除（rm 项目级重写
        // settings.json、rm 会话级落 permission/rules 事件快照）
        commands.register(ctx, new CommandDefinition("permission",
                "查看或切换权限预设：/permission [read-only|workspace-write|danger-full-access]"
                        + "；/permission rules [list|rm <P#|S#>]",
                CommandScope.ANY, true, context -> {
                if (workspacePolicy == null) {
                    return "workspace 服务未挂载（未装配 fs 工具插件），/permission 不可用。";
                }
                String input = context.args().strip();
                if (input.equals("rules") || input.startsWith("rules ")) {
                    return permissionRulesCommand(ctx, context,
                            input.length() > "rules".length() ? input.substring("rules".length()).strip() : "");
                }
                if (input.isEmpty()) {
                    return "当前预设: " + workspacePolicy.mode().configName()
                            + "（可选: read-only / workspace-write / danger-full-access；"
                            + "/permission rules 管理权限规则）";
                }
                try {
                    workspacePolicy.setMode(WorkspacePolicy.Mode.parse(input));
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
     * REPL 读者循环（M23 事件驱动，ADR-0025 决策一）：常驻读 stdin——空闲行走命令
     * 分发与 turn 启动；busy 行普通文本注入收件箱 next-step 级（插队回显）、斜杠命令
     * 照走注册表、应答行经闸门路由回答者；EOF 时不腰斩执行中的 turn（join 后收尾）。
     * turn 本体跑在 cli-agent 虚拟线程，收口后消费 next-turn 队列（多条合并为一条）。
     */
    private void replLoop(CommandsRegistry commands, SkillRegistry skills, SessionHolder holder,
                          PlanHolder plan, AgentHolder agentHolder, AtomicBoolean agentBusy,
                          AtomicBoolean interruptArmed) {
        try {
            while (!stopped.get() && !endRequested.get()) {
                out.print(backgroundHint() + "你> ");
                out.flush();
                String line = in.readLine();
                if (line == null) {
                    answerGate.close(); // 在飞应答随 EOF 立即 fail-closed，随后 join 收尾
                    awaitTurnThread();
                    break;
                }
                handleLine(line.strip(), commands, skills, holder, agentHolder,
                        agentBusy, interruptArmed);
            }
        } catch (IOException e) {
            // stop() 关闭输入流打断阻塞读——按 /exit 同语义收尾（idle）
        }
        goIdle(holder, plan);
    }

    /** 单行路由（M23 事件驱动）：应答闸门 → busy 插队/命令 → idle 分发与 turn 启动。 */
    private void handleLine(String input, CommandsRegistry commands, SkillRegistry skills,
                            SessionHolder holder, AgentHolder agentHolder,
                            AtomicBoolean agentBusy, AtomicBoolean interruptArmed) {
        if (input.isEmpty()) {
            return;
        }
        // 应答行优先：审批/提问在飞时输入行是给回答者的（y/n、序号、自由文本）——
        // 斜杠行例外（M23 工单 03 审查发现）：应答等待期的 /stop 若被闸门吞作应答，
        // 中断入口即被挡死；命令行逃逸闸门走 busy 分发（/stop 即行、其余照旧分级）
        if (answerGate.pending() && !input.startsWith("/")) {
            answerGate.offer(input);
            return;
        }
        if (agentBusy.get()) {
            handleBusyLine(input, commands, skills, holder, agentHolder, agentBusy);
            return;
        }
        handleIdleLine(input, commands, skills, holder, agentHolder, agentBusy, interruptArmed);
    }

    /** 空闲行（与阻塞时代同语义）：dispatch（命令 → 技能直调 → 未知报错）→ 透传文本开 turn。 */
    private void handleIdleLine(String input, CommandsRegistry commands, SkillRegistry skills,
                                SessionHolder holder, AgentHolder agentHolder,
                                AtomicBoolean agentBusy, AtomicBoolean interruptArmed) {
        CommandOutcome outcome = commands.dispatch(input,
                new CommandEnv(CommandScope.CLI, holder::current, out::println,
                        () -> endRequested.set(true), agentBusy::get),
                skills);
        if (endRequested.get()) {
            return; // /exit：请求结束回调已置位（done 审计已落盘），读者循环随即跳出
        }
        String userText;
        if (outcome.isCommand()) {
            if (!outcome.text().isEmpty()) {
                out.println(outcome.text());
                out.flush();
            }
            if (outcome.forward() == null) {
                return;
            }
            userText = outcome.forward(); // /plan 携任务描述：回显结果后再推进
        } else {
            userText = outcome.text();
        }
        // CAS 封口：与后台通知线程的开轮竞争败北时改插队（消息不丢）
        if (!agentBusy.compareAndSet(false, true)) {
            agentHolder.agent.injectUserMessage(userText);
            return;
        }
        startTurn(userText, agentHolder, agentBusy, interruptArmed);
    }

    /**
     * busy 行（M23 事件驱动，修复"执行期输入被当新输入消费"）：普通文本/技能直调注入
     * 收件箱 next-step 级并回显「已插队」；斜杠命令照走注册表——busySafe 立即执行，
     * 非 busySafe 得到等待回应（BUSY_REFUSAL，ADR-0020 决策 4）；命令转发文本同走插队。
     */
    private void handleBusyLine(String input, CommandsRegistry commands, SkillRegistry skills,
                                SessionHolder holder, AgentHolder agentHolder,
                                AtomicBoolean agentBusy) {
        CommandOutcome outcome = commands.dispatch(input,
                new CommandEnv(CommandScope.CLI, holder::current, out::println,
                        () -> {
                        }, agentBusy::get),
                skills);
        if (outcome.isCommand()) {
            if (!outcome.text().isEmpty()) {
                out.println();
                out.println("  " + outcome.text());
                out.flush();
            }
            if (outcome.forward() != null) {
                steerIntoNextStep(outcome.forward(), agentHolder);
            }
            return;
        }
        steerIntoNextStep(outcome.text(), agentHolder);
    }

    /** next-step 插队 + 提示行回显（ADR-0025 决策一）：注入失败（不支持）时如实提示。 */
    private void steerIntoNextStep(String text, AgentHolder agentHolder) {
        boolean accepted = agentHolder.agent.injectUserMessage(text);
        String preview = text.split("\n", 2)[0];
        if (preview.length() > 40) {
            preview = preview.substring(0, 40) + "…";
        }
        if (accepted) {
            out.println("  [已插队] " + preview + "（下一步边界生效）");
        } else {
            out.println("  [提示] 当前 agent 不支持运行中注入，输入未送达。");
        }
        out.flush();
    }

    /**
     * 启动 turn 线程（cli-agent）：单飞标志置位后再派生（读者线程随后即见 busy）；
     * 收口后消费 next-turn 队列——多条合并为一条立即开新轮（ADR-0025 决策一/二），
     * 直到队列空或收到退出/停止。
     */
    private boolean startTurn(String userText, AgentHolder agentHolder, AtomicBoolean agentBusy,
                              AtomicBoolean interruptArmed) {
        // busy 占位由调用方 CAS 完成（M23 工单 04 审查修复：通知线程与读者线程的
        // 开轮竞争在调用方用 compareAndSet 封口，本方法信任占位无条件执行）
        Runnable turn = () -> {
            try {
                String current = userText;
                while (current != null && !stopped.get() && !endRequested.get()) {
                    runOneTurn(current, agentHolder, interruptArmed);
                    java.util.List<String> queued = agentHolder.agent.drainNextTurn();
                    if (queued.isEmpty()) {
                        break;
                    }
                    current = String.join("\n\n", queued);
                    out.println("  [排队消息生效]");
                    out.flush();
                }
            } finally {
                agentBusy.set(false);
            }
        };
        // 先登记后启动（审查修复）：stop 在登记与启动的窗口内也能命中打断——
        // NEW 态线程 interrupt 是无害空操作；停止后不启动，busy 标志就地复位
        Thread thread = Thread.ofVirtual().name("cli-agent").unstarted(turn);
        turnThread = thread;
        if (stopped.get()) {
            agentBusy.set(false);
            return true;
        }
        thread.start();
        return true;
    }

    /** 单轮执行：send + 流式渲染 + 终态行（中断/异常收敛为提示行，不终结事件循环）。 */
    private void runOneTurn(String userText, AgentHolder agentHolder, AtomicBoolean interruptArmed) {
        try {
            var reply = agentHolder.agent.send(userText, turnListener());
            if (reply.interrupted()) {
                out.println("  [已中断] 当前任务已暂停——已流出内容保留在会话中，"
                        + "发送新消息即可从中断处续接。");
            } else if (!reply.completed()) {
                out.println("  [异常终止] " + reply.finalText());
            }
        } catch (PluginException e) {
            out.println("  [错误] " + e.getMessage());
        } catch (RuntimeException e) {
            // turn 线程不静默死亡（审查修复）：非 Plugin 异常收敛为错误行，事件循环存活
            out.println("  [错误] 执行异常: "
                    + (e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            interruptArmed.set(false); // 中断收口完成，再按闸复位
        }
        out.println();
        out.flush();
    }

    /** 流式渲染监听（工具叙述行通用形态，M12-03 起零工具特化；清单动作行除外）。 */
    private AgentListener turnListener() {
        return new AgentListener() {
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
        };
    }

    /**
     * 应答闸门：审批/提问/计划的输入行通道（M23 工单 01）。回答者经 {@link #awaitLine()}
     * 取行（置位 pending），读者线程见 pending 即把行路由进来；EOF/stop 置 closed 后
     * 取行按 null 返回（fail-closed）——即使审批在 EOF 之后才发起也不挂死（审查修复）。
     *
     * <p>已知边界：pending 置位发生在回答者被询问之后——读者查询与置位间的微窗内键入
     * 的行会被当 steer 注入（无害：模型可见该文本；真实输入秒级 vs 窗口微秒级）。</p>
     */
    private static final class AnswerGate {
        private final java.util.concurrent.BlockingQueue<String> lines =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private final AtomicBoolean pending = new AtomicBoolean(false);
        private volatile boolean closed = false;

        /** 读者线程投递一行（y/n、序号、自由文本）。 */
        void offer(String line) {
            lines.offer(line);
        }

        /** 输入终结（EOF / stop）：等待中的取行立即 fail-closed 返回。 */
        void close() {
            closed = true;
            lines.offer("");
        }

        boolean pending() {
            return pending.get();
        }

        /** 回答者取行：阻塞至有行/终结/打断；终结与打断均按 null（fail-closed）返回。 */
        String awaitLine() {
            pending.set(true);
            try {
                while (!closed) {
                    String line = lines.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (line != null) {
                        return line;
                    }
                }
                return null;
            } catch (InterruptedException e) {
                // 协作式中断唤醒（M23 工单 03）：不重设线程标志——中断语义由
                // interruptRequested 布尔承载，残留标志会炸后续 NIO 落盘
                // （审批审计事件的 append 在工具线程上，ClosedByInterruptException）
                return null;
            } finally {
                pending.set(false);
            }
        }
    }

    /**
     * 后台提示段（M23 工单 06 可见化）：有运行中/已终态后台任务时，提示符前缀带
     * 计数与最近完成——无任务返回空串（零噪声）。
     */
    private String backgroundHint() {
        return backgroundHint(backgroundTasks);
    }

    /** 纯函数形态（可直测，M23 工单 06）：hint 渲染只依赖注册表快照。CLI 只看本位
     * 发起（或无归属）的任务——Web 侧任务不占终端提示符（M23 工单 06 验收修正）。 */
    static String backgroundHint(dev.duo.harness.tools.fs.BackgroundTaskRegistry registry) {
        var mine = ownedTasks(registry, dev.duo.harness.agent.ChatAgent.PRESENTER_CLI);
        if (mine.isEmpty()) {
            return "";
        }
        long running = mine.stream()
                .filter(t -> t.state() == dev.duo.harness.tools.fs.BackgroundTask.State.RUNNING).count();
        if (running > 0) {
            return "[后台 " + running + " 个运行中] ";
        }
        var latest = mine.get(mine.size() - 1);
        return "[后台已完成 " + latest.taskId() + "] ";
    }

    /** 归属过滤（呈现位复用 presenterId，M19）：本位发起或无归属（null，双面可见）。 */
    static List<dev.duo.harness.tools.fs.BackgroundTask> ownedTasks(
            dev.duo.harness.tools.fs.BackgroundTaskRegistry registry, String presenterId) {
        if (registry == null) {
            return List.of();
        }
        return registry.all().stream()
                .filter(t -> t.owner() == null || t.owner().equals(presenterId))
                .toList();
    }

    /** 读者线程等执行中的 turn 收尾（EOF 不腰斩在飞任务；stop 打断即返回）。 */
    private void awaitTurnThread() {
        Thread thread = turnThread;
        if (thread == null) {
            return;
        }
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    /**
     * SIGINT（Ctrl+C）三态拦截（M23 工单 02，ADR-0025 决策一）：运行期单击 = 协作式中断
     * （再按一次强制退出 130）；空闲单击 = 退出进程（shutdown hook 级联 dispose）。
     * 注册以 {@code System.console() != null} 为门：真实终端才拦截——测试/管道/
     * headless 场景保留 JVM 默认终止（headless --json 的 SIGINT=130 退出码契约天然成立，
     * ADR-0025）。{@code sun.misc.Signal} 为 HotSpot 内部 API（事实上稳定）——环境
     * 不支持时静默跳过，暂停仍有 /stop 行命令兜底。
     */
    private void registerSigintHandler(AgentHolder agentHolder, AtomicBoolean agentBusy,
                                       AtomicBoolean interruptArmed) {
        if (System.console() == null) {
            return; // 非交互终端：不拦截，保留默认终止语义
        }
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("INT"), sig -> {
                if (agentBusy.get()) {
                    // 置 armed 以「中断已被 agent 接受」为前提（审查修复）：busy 但 send
                    // 已收口的互斥窗口里 requestInterrupt 返回 false——不当"再按"记账，
                    // 否则新 turn 的第一次 Ctrl+C 会被误判为二次而直接退出
                    if (!agentHolder.agent.requestInterrupt()) {
                        return; // 收口竞态窗口：本次按键作废，稍候即空闲
                    }
                    if (interruptArmed.compareAndSet(false, true)) {
                        out.println();
                        out.println("  [中断] 已请求中断当前任务；再按一次 Ctrl+C 强制退出。");
                        out.flush();
                    } else {
                        Runtime.getRuntime().exit(130); // 二次 Ctrl+C：强制退出（SIGINT 惯例码）
                    }
                } else {
                    Runtime.getRuntime().exit(0); // 空闲：退出进程（shutdown hook 级联清理）
                }
            });
        } catch (Throwable unsupported) {
            // Signal 不可用（非 HotSpot 等）：提示降级路径（可发现性），/stop 行命令仍可用
            out.println("[提示] 当前环境不支持 Ctrl+C 拦截，暂停请使用 /stop 命令。");
            out.flush();
        }
    }

    /** 插件停止（树 dispose / shutdown hook 调用）：打断阻塞读与执行中的 turn，按 idle 收尾。 */
    private void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            in.close(); // 解除读者线程 readLine 阻塞（System.in 的关闭无害——进程正在退出）
        } catch (IOException ignored) {
            // 已关
        }
        if (replThread != null) {
            replThread.interrupt();
        }
        answerGate.close(); // 在飞应答随停止立即 fail-closed
        Thread activeTurn = turnThread;
        if (activeTurn != null) {
            activeTurn.interrupt(); // NEW 态为无害空操作；已启动的在飞应答/执行随打断收敛
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

    /** files 投递开关与 provider 连接（llm.imageDelivery=files 时，M21 工单 06；loadLlm 刷新）。 */
    private static volatile boolean filesDeliveryEnabled;
    private static volatile String deliveryBaseUrl;
    private static volatile String deliveryApiKey;

    /** files 投递服务实例（apply 时按开关构建；/new 换绑 rebuild 沿用）。 */
    private volatile dev.duo.harness.attachment.ImageFileDelivery fileDelivery;

    /** LLM 装配：配置缺失时 FAILED 并给出示例（沿 CLI 既有提示形态）。 */
    private LlmAdapter loadLlm() {
        try {
            dev.duo.harness.llm.LlmConfig cfg = dev.duo.harness.llm.LlmConfig.load();
            visionEnabled = cfg.vision();
            filesDeliveryEnabled = dev.duo.harness.llm.LlmConfig.DELIVERY_FILES.equals(cfg.imageDelivery());
            deliveryBaseUrl = cfg.baseUrl();
            deliveryApiKey = cfg.apiKey();
            activeConfig = cfg;
            // 可换执行链（M24 工单 09）：/model 切换的 swap 入口——治理与 agent 共享同一装饰器
            return new dev.duo.harness.llm.SwappableLlmAdapter(PresenterAssembly.llmAdapter(cfg));
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

    /** memory 服务的视图接口（方法名即服务名 "memory"，M25 工单 02）。 */
    interface CliMemoryView {

        dev.duo.harness.agent.memory.MemoryBook memory();
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

    /** 权限规则服务的视图接口（服务名 permissionRules，M24 工单 01）。 */
    interface CliPermissionRulesView {

        dev.duo.harness.tools.fs.PermissionRules permissionRules();
    }

    /** 连接器状态板的视图接口（服务名 connectorStatus，M24 工单 05）。 */
    interface ConnectorStatusView {

        dev.duo.harness.tools.ConnectorStatusBoard connectorStatus();
    }

    /** 后台任务注册表的视图接口（服务名 backgroundTasks，M23 工单 04）。 */
    interface BackgroundTasksView {

        dev.duo.harness.tools.fs.BackgroundTaskRegistry backgroundTasks();
    }
}
