package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.agent.AuditingAnswerer;
import dev.duo.harness.agent.prompt.PromptRegistry;
import dev.duo.harness.agent.SessionTitles;
import dev.duo.harness.agent.presenter.PresenterAssembly;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.agent.commands.CommandScope;
import dev.duo.harness.core.api.boot.Cwd;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.commands.CommandsRegistry;
import dev.duo.harness.agent.governance.ContextGovernance;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolsService;
import dev.duo.harness.tools.fs.WorkspacePolicy;

import java.nio.file.Path;
import java.util.Set;

/**
 * Web 双面插件（M8 起，M10 加固）：Boot yml 一行启用本地 Web 服务——静态单页
 * （对话/状态双区）、`/api/status` 状态 JSON（含上下文占用）、`/api/events` SSE
 * 会话事件流（首连快照 / 重连游标增量，ADR-0010）、`/api/message` 对话入口、
 * `/api/session/*` 会话列换与切换（切换有独占锁语义）。只绑 127.0.0.1，
 * 默认 token 鉴权（config.auth: token|none，M24 工单 06——显式关闭时启动横幅警示）。
 *
 * <p>inject tools + prompts + answers：状态面与对话面的三个数据源（标准服务注入
 * 模式）。技能清单 / AGENTS.md 片段由对应插件（SkillsPlugin / AgentsMdPlugin）
 * 注册进 prompts 服务——本插件只做对话执行者装配，不重复注册。装配链还负责：
 * 注册 ask_user 与计划呈交工具（纯 Web 部署的 HITL 完整，与 CLI 装配共存时先到先得）、
 * 装配 Web answerer 与审计桥、启动自建全新会话（BUG-20260923-01：不续接不抢占——
 * CLI 侧 resume 语义不受影响，恢复历史对话走 /switch）。</p>
 *
 * <p>LLM 未配置时插件 FAILED 点名（整个 Web 面不可用——LLM 配置先于服务启动装载）。</p>
 *
 * <p>配置（块内字段可省）：</p>
 * <pre>{@code config:
 *   port: 8080        # 监听端口（省略默认 8080；只绑 127.0.0.1。覆盖优先级：系统属性
 *                     # duo.web.port > 环境变量 DUO_WEB_PORT > 本值，M37 工单 01——桌面壳注入口）
 *   maxIterations: 30 # 单轮对话迭代上限（省略默认 10；计划模式等探索型任务建议调高）
 *   maxParallelToolCalls: 10 # 单轮并发安全工具并行上限（省略默认 10；=1 即完全串行，排障用）
 *   pipelineTimeoutMs: 120000 # 工具执行管线缺省超时毫秒（省略默认 120s）
 *   governance: {}    # 上下文治理阈值段（省略即缺省常量）}</pre>
 */
public final class WebPlugin implements Plugin<JsonNode> {

    /** 呈现位身份（M28 工单 05）：id 由呈现位自行声明——内核不再钉死合法 id 集，
     * 第三呈现位零内核改动接入（回答者注册面即在场登记）。 */
    public static final String PRESENTER_ID = "web";

    /** 默认监听端口。 */
    public static final int DEFAULT_PORT = 8080;

    /** 端口合法下界（0 = 随机分配语义，WebFace 既有）。 */
    public static final int MIN_PORT = 0;

    /** 端口合法上界。 */
    public static final int MAX_PORT = 65535;

    /** 端口覆盖的系统属性名（测试注入专用口，优先级最高——DuoHome 同构）。 */
    public static final String PORT_PROP_OVERRIDE = "duo.web.port";

    /** 端口覆盖的环境变量名（桌面壳注入口，ADR-0039 决策六：壳选端口注入，装配文件不动）。 */
    public static final String PORT_ENV_OVERRIDE = "DUO_WEB_PORT";

    /** 悬空作答的兜底超时（无断连触发时的最终收口，10 分钟）。 */
    private static final long ANSWER_TIMEOUT_MS = 10 * 60 * 1000L;

    private WebFace face;

    /** Web 面当前 LLM 配置（/model、/effort 切换的状态源，M38 工单 01）。 */
    private volatile LlmConfig activeConfig;

    /** Web 面可换执行链（/model 切换的 swap 入口；治理与 agent 共享同一装饰器）。 */
    private volatile dev.duo.harness.llm.SwappableLlmAdapter swappableLlm;

    /** 测试缝（包私有）：装配测试取实际绑定端口与 token——port 0 随机端口不可预知。 */
    WebFace face() {
        return face;
    }

    @Override
    public Set<String> inject() {
        return Set.of(ToolsService.SERVICE_NAME, PromptRegistry.SERVICE_NAME,
                dev.duo.harness.agent.commands.ModelSwitchRegistry.SERVICE_NAME,
                InteractionService.SERVICE_NAME, CommandsRegistry.SERVICE_NAME);
    }

    /**
     * workspace 为可选依赖（ADR-0019）：权限档恢复
     * （PresenterAssembly.restorePermissionMode）经本插件 Context 惰性解析
     * workspace——未声明时内核"错误前移"拒绝读取，Web 侧权限档恢复被跳过。
     * 缺席（纯对话 Web 装配）视为无档位语义，照常启动（/permission 命令由 CLI 面
     * 注册，浏览器切档的可用性随 cli 行在场与否）。
     */
    @Override
    public Set<String> optionalInject() {
        return Set.of(WorkspacePolicy.SERVICE_NAME,
                dev.duo.harness.attachment.AttachmentStore.SERVICE_NAME,
                dev.duo.harness.sessionquery.SessionQueryService.SERVICE_NAME,
                dev.duo.harness.tools.fs.BackgroundTaskRegistry.SERVICE_NAME,
                dev.duo.harness.tools.fs.PermissionRules.SERVICE_NAME,
                dev.duo.harness.agent.memory.MemoryBook.SERVICE_NAME,
                dev.duo.harness.agent.prompt.AgentsMdChain.SERVICE_NAME,
                // 连接器状态板（BUG-20261002-01）：MCP 行在场时状态面连接器块经插件
                // Context 读板——未声明则 hasService 真 ≠ 可读（内核错误前移拒读），
                // statusJson 抛异常且 route 无兜底 → 连接裸关（状态面全盲实测形态）
                dev.duo.harness.tools.ConnectorStatusBoard.SERVICE_NAME,
                // 技能注册表（BUG-20261002-06）：斜杠解释链第二级「技能直调」经
                // skillsOrNull 读注册表——未声明则被声明闸门拒读并被 catch 吞成 null，
                // 直调级永远未命中（/技能名 全部「未知命令」且提示不带技能清单）
                dev.duo.harness.agent.skills.SkillRegistry.SERVICE_NAME);
    }

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        int port = resolvePort(config);
        // 鉴权令牌（M24 工单 06，ADR-0026 决策五）：缺省生成随机令牌（fail-closed），
        // web.auth: none 显式关闭（启动横幅警示）；非法值 FAILED 点名
        String authMode = parseAuth(config);
        String authToken = "token".equals(authMode) ? generateToken() : null;
        ToolsService tools = ctx.as(WebToolsView.class).tools();
        PromptRegistry prompts = ctx.as(WebPromptsView.class).prompts();
        InteractionService answers = ctx.as(WebAnswersView.class).answers();

        // 行序契约 fail-fast（M27 工单 05）：回答者注册序即亲和路由
        // 兜底序（ADR-0020 决策 7）——web 必须先于既有回答者注册（agent-demo 契约：
        // web 行先于 cli 行，审批/提问 Web 卡片优先）。既有回答者在场即本行装配过晚：
        // 启动期点名（Boot 审计整树回滚），不再静默吞路由语义（BUG-20260923-01 实证）
        if (answers.hasAnswerer()) {
            throw new PluginException("行序契约：web 行必须先于任何回答者注册（典型为 cli 行先行）"
                    + "——交互服务已有回答者在册，web 后注册将失去路由兜底优先（审批/提问应 Web"
                    + " 卡片优先）。请将 web 行移至 cli 行之前后重启。");
        }
        CommandsRegistry commands = ctx.as(WebCommandsView.class).commands();

        // 执行链装配（呈现位共享单点，ADR-0011）：LLM 配置 → 可换装饰器包装重试
        // adapter（M38 工单 01，ADR-0040 决策三：/model、/effort 升 ANY 双面后 Web 面
        // 的换链绑定——本呈现位独立，Web 切只影响 Web 链）；LLM 未配置 → 插件 FAILED 点名
        LlmConfig llm = LlmConfig.load();
        var adapter = new dev.duo.harness.llm.SwappableLlmAdapter(PresenterAssembly.llmAdapter(llm));
        this.activeConfig = llm;
        this.swappableLlm = adapter;
        // 模型/思考切换登记（M38 工单 01）：登记 WEB 控制器 + 注册双面命令（查重先到
        // 先得——web 行先于 cli 行，本注册生效、CliPlugin 查重跳过）；handler 按发起
        // 呈现位取控制器，本呈现位独立换链
        dev.duo.harness.agent.commands.ModelSwitchRegistry modelSwitch =
                ctx.as(WebModelSwitchView.class).modelSwitch();
        modelSwitch.register(CommandScope.WEB, new WebModelSwitchController());
        PresenterAssembly.registerModelSwitchCommands(ctx, commands, modelSwitch);
        // 附件库（M21，可选依赖）：纯对话 Web 装配缺席时端点 503、带图消息 409；
        // vision=true 时构建请求变体解析器（附件引用 → base64 图片部件）
        dev.duo.harness.attachment.AttachmentStore attachments =
                ctx.hasService(dev.duo.harness.attachment.AttachmentStore.SERVICE_NAME)
                        ? ctx.as(WebAttachmentsView.class).attachments() : null;
        dev.duo.harness.attachment.RequestVariants variants = attachments == null ? null
                : new dev.duo.harness.attachment.RequestVariants(attachments,
                        DuoHome.resolve().root().resolve("cache/attachments"));
        // files 投递（M21 工单 06）：vision 且 imageDelivery=files 时变体上传 Files API
        // 换 file_id（本地索引去重 + 配额回收）；上传失败由投递调用方回退 inline
        dev.duo.harness.attachment.ImageFileDelivery fileDelivery =
                attachments != null && llm.vision()
                        && dev.duo.harness.llm.LlmConfig.DELIVERY_FILES.equals(llm.imageDelivery())
                        ? new dev.duo.harness.attachment.ImageFileDelivery(
                                new dev.duo.harness.attachment.FilesApiUploader(
                                        llm.baseUrl(), llm.apiKey(), java.time.Duration.ofSeconds(60)),
                                DuoHome.resolve().root()
                                        .resolve("cache/attachments/files-index.json"))
                        : null;
        // read_image 视觉闸门回填（M21 收口修正）：fs 插件注册时 vision 真值不可得，
        // 呈现位加载 llm 配置后回填（llm.vision）
        PresenterAssembly.wireReadImageVisionGate(tools, llm.vision());
        // 会话检索（M21 工单 08，可选依赖）：session-query 行缺席时侧栏搜索 503 降级
        dev.duo.harness.sessionquery.SessionQueryService sessionQuery =
                ctx.hasService(dev.duo.harness.sessionquery.SessionQueryService.SERVICE_NAME)
                        ? ctx.as(WebSessionQueryView.class).sessionQuery() : null;
        // @file 补全服务（M21 工单 07，ADR-0022 决策 7）：workspace 在场才建——
        // 索引以 workspace 根为界。本插件自产自用（补全端点经门面直传取用），**不进
        // inject/optionalInject 声明**：自产自依赖会让内核 epoch 指纹随每次 provide
        // 的新实例翻动、recheck 循环重跑 apply（M28 工单 07 实测验证后回退——服务化
        // 正解需提供方归位 fs 插件，见 backlog 挂账）
        dev.duo.harness.agent.fileref.FileReferenceService fileRefs =
                ctx.hasService(WorkspacePolicy.SERVICE_NAME)
                        ? new dev.duo.harness.agent.fileref.FileReferenceService(
                                ctx.as(WebWorkspaceView.class).workspace().root())
                        : null;
        // plan 态 bash 只读判定器（M24 工单 04）：workspace 在场时构建（与裁决链 detector
        // 同源同参，只读无状态）；缺席即 null，plan 态 bash 到达 fail-closed 拒
        dev.duo.harness.tools.fs.ReadOnlyBashDetector planBashDetector =
                ctx.hasService(WorkspacePolicy.SERVICE_NAME)
                        ? new dev.duo.harness.tools.fs.ReadOnlyBashDetector(
                                ctx.as(WebWorkspaceView.class).workspace().root())
                        : null;
        // 记忆本可选依赖（M25 工单 02）：memory 行缺席零感降级（零注入）
        dev.duo.harness.agent.memory.MemoryBook memory =
                ctx.hasService(dev.duo.harness.agent.memory.MemoryBook.SERVICE_NAME)
                        ? ctx.as(WebMemoryView.class).memory() : null;
        // AGENTS.md 链可选依赖（M25 工单 07）：agents-md 行缺席零感降级（零注入）
        dev.duo.harness.agent.prompt.AgentsMdChain agentsMd =
                ctx.hasService(dev.duo.harness.agent.prompt.AgentsMdChain.SERVICE_NAME)
                        ? ctx.as(WebAgentsMdView.class).agentsMd() : null;

        // Web 面自建 deferred 会话（BUG-20260923-01 隔离 + M30 工单 05 空会话根治）：
        // 不再续接目录最新——web 行先于 cli 行装配（回答者路由契约：审批/提问 Web 卡片
        // 优先），latest 会抢走 CLI 的续接目标，CLI 随后撞同进程持锁注册表被顶开新建，
        // resume 续接语义（模型意图横幅）就此永远失效。deferred 仅内存态：不落文件、
        // 不进文件列表（侧栏当前项由 sessionsJson 合成条目呈现），恢复上次 Web 对话走
        // /switch。
        Session session = Session.createDeferred(DuoHome.resolve().resolveDir("agent-sessions"),
                Cwd.path());
        dev.duo.harness.agent.deliverable.ChangeSummary.markStart(session);
        // 上下文治理（M9）：初始与 /new、/switch 重建共用同一治理配置；governance 段
        // 可省（缺省常量，0.7.0 行为），配置错误（未知字段/类型/越界）启动即 FAILED 点名
        ContextGovernance.Tuning governanceTuning = PresenterAssembly.parseGovernance(config);
        ContextGovernance governance = PresenterAssembly.governance(adapter, governanceTuning);
        // 迭代上限（BUG-20260917-03）：config.maxIterations 可省，缺省内核常量（10）
        int maxIterations = PresenterAssembly.parseMaxIterations(config);
        // 并发度（ADR-0018）：config.maxParallelToolCalls 可省，缺省 10；=1 即完全串行
        int maxParallelToolCalls = PresenterAssembly.parseMaxParallelToolCalls(config);
        // 管线缺省超时（ADR-0018）：config.pipelineTimeoutMs 可省，缺省 120s——挂工具执行段兜底
        PresenterAssembly.mountPipelineTimeout(ctx, tools, PresenterAssembly.parsePipelineTimeoutMs(config));
        // agent 构建单点（C2 工单 06）：初始会话与 onSessionChanged 换绑共用同一构建
        // 函数——此前两处逐参平行展开，新增能力项漏改一处即「初始与换绑行为分叉」
        java.util.function.Function<dev.duo.harness.session.Session, ChatAgent> buildAgent =
                s -> PresenterAssembly.chatAgent(new dev.duo.harness.agent.AgentSpec(
                        adapter, tools, s, prompts, maxIterations, maxParallelToolCalls,
                        PRESENTER_ID,
                        new dev.duo.harness.agent.AgentCapabilities(governance, variants, llm.vision(),
                                fileDelivery,
                                planBashDetector == null ? null : planBashDetector::isReadOnlyBash,
                                memory == null ? null : memory::metaUserSection,
                                agentsMd == null ? null : agentsMd::section)));
        ChatAgent agent = buildAgent.apply(session);
        // fileRefs 失效监听挂载单点（C2 工单 06）：初始会话与换绑回调共用——
        // 此前同一段监听器代码两份展开
        java.util.function.Consumer<dev.duo.harness.session.Session> attachFileRefs =
                s -> {
                    if (fileRefs != null) {
                        s.addListener((index, event) -> {
                            if (dev.duo.harness.session.SessionEvent.TOOL_RESULT.equals(event.type())) {
                                fileRefs.markStale();
                            }
                        });
                    }
                };
        // HITL Web answerer：注册进交互 seam（断连 fail-closed 由 WebFace 联动）
        WebAnswerer webAnswerer = new WebAnswerer(ANSWER_TIMEOUT_MS);

        // 页长解析在 start 之前——PluginException 不入下方 IOException catch，
        // 确保配置错误路径也走 session.close() 释放独占锁（OCR #17）
        int pageSize = parsePageSize(config);
        try {
            face = WebFace.start(port, ctx, tools, session, agent, governance, webAnswerer,
                    DuoHome.resolve().resolveDir("agent-sessions"), pageSize,
                    attachments, () -> llm.vision(), sessionQuery, authToken);
            // 后台任务完成通知路由（M23 工单 04）：fs 行在场时挂载——注册表缺席零感
            if (ctx.hasService(dev.duo.harness.tools.fs.BackgroundTaskRegistry.SERVICE_NAME)) {
                face.setBackgroundTaskRegistry(ctx.as(WebBackgroundTasksView.class).backgroundTasks());
            }
            // 端点贡献口（ADR-0037 内核受控口二）：发布 webRoutes 服务——插件按前缀
            // 申请制挂端点（插件中心是第一个消费者）；贡献路由的摘除由消费方把 claim
            // 移除器挂自己作用域，本服务注销随本插件拔除自动发生
            ctx.provide(WebRouteRegistry.SERVICE_NAME, face.routes());
            // 呈现贡献口（ADR-0038 决策四）：发布 presentationRegistry 服务——插件按
            // 申请制注册主题与展示卡声明（改值不改构：声明经内核校验后经聚合端点
            // 序列化下发，无直达前端注入面）；无 Web 部署下消费方 optionalInject 优雅缺席
            ctx.provide(PresentationRegistry.SERVICE_NAME, face.presentation());
        } catch (java.io.IOException e) {
            session.close(); // 启动失败即释放会话独占锁：不给失败的启动留占用
            throw new PluginException("Web 服务启动失败（端口 " + port + "）", e);
        }
        // 审计桥包装（ADR-0008 决策 5）：approval/requested、approval/decided 事件落会话
        // ——会话监听器推 SSE，页面据此渲染审批卡；会话经 face 延迟解析（/new 换绑后留新会话）。
        // 「总是允许」规则生成包装（M24 工单 02）：规则服务在场时拦截 allowAlways 生成规则
        // （项目级写 settings.json、会话级落当前会话事件），缺席原样透传
        answers.register(ctx, new AuditingAnswerer(face::currentSession,
                wrapRuleGenerating(ctx, webAnswerer)));
        // /compact（M19，ADR-0020 决策 6）：双面命令随 Web 装配注册（查重先到先得——
        // CLI 已注册则跳过），会话经 face 延迟解析取当前值
        PresenterAssembly.registerCompactCommand(ctx, commands, governance);
        PresenterAssembly.registerTitleCommand(ctx, commands);
        // /export（M21 工单 09，ADR-0022 决策 9）：双面命令（查重先到先得）；Web 发起
        // 时返回下载端点 URL，前端拦截自动触发下载流
        PresenterAssembly.registerExportCommand(ctx, commands);
        // 权限档持久化（M19，ADR-0020 决策 10）：启动续接只恢复不重置（BUG-20260919-03
        // ——双开下另一呈现位可能刚恢复过档位）；换绑恢复在 onSessionChanged 回调里执行
        PresenterAssembly.restorePermissionMode(ctx, session, false);
        // 会话级权限规则恢复（M24，ADR-0026 决策一）：续接该会话的规则快照
        PresenterAssembly.restorePermissionRules(ctx, session);
        // HITL 交互工具补全（共享装配器，查重先到先得）：ask_user 与计划呈交随 Web 装配
        // 注册——纯 Web 部署（无终端）下提问卡/计划卡的供给到位，HITL 不依赖 CLI 装配
        // 在场。会话经 face 延迟解析；Web 面不挂计划指导片段，退出回调无状态可清
        PresenterAssembly.registerInteractionTools(
                ctx, tools, answers, PRESENTER_ID, face::currentSession, () -> { });
        // todo 分解抓手（ADR-0018）：呈现状态工具随装配注册（与交互工具同供给模式）
        PresenterAssembly.registerTodoWriteTool(ctx, tools, face::currentSession);
        // present 退役（M29 工单 12 用户裁定）：成果申报工具不再注册——交付物呈现改由
        // Web 前端 ZCode 式预览卡自动推导（回复文本提取产物路径）；PresentTool/
        // ChangeSummary 类保留（历史 deliverable 事件导出汇总仍走 ChangeSummary）
        // subagent 宿主发布（M15，ADR-0015）：发布父侧执行链构件——SubagentPlugin
        // 在场且配置了模板时自行装配五件工具；未配置部署零感知（只发服务，零工具）
        PresenterAssembly.publishSubagentHost(ctx, adapter, governanceTuning, face::currentSession);
        // /new：全新会话；/switch：换绑既有会话；新标签首请求：懒创建——三者换绑后都经
        // 会话变更回调重建 agent（ToolCallingAgent 持有 final 会话引用，不重建即分脑）。
        // 回调**返回**新 agent 归标签上下文（M24 工单 07）——不再有全局单槽 setAgent
        face.onNewSession(() -> Session.createDeferred(DuoHome.resolve().resolveDir("agent-sessions"),
                Cwd.path()));
        // 会话变更回调是单回调槽（覆盖式 setter，非多播）——全部换绑动作必须合并在这一次
        // 注册里。教训（BUG-20260916-01）：第二处注册会覆盖"换绑重建 agent"，切回分脑
        //（agent 写已 close 的旧会话，发消息必报错）。标题生成（工单 M13-06）随换绑同源
        // attach，双开时与 CLI 共享静态去重表
        face.onSessionChanged(fresh -> {
            // 变更摘要首拍（M26-05）：全部换绑动作（/new、/switch、懒创建）经此单回调
            dev.duo.harness.agent.deliverable.ChangeSummary.markStart(fresh);
            SessionTitles.attach(fresh, adapter);
            // 显式换绑（新话题/切换/新标签）：无切档记录即重置回 yml 缺省（ADR-0020 决策 10）
            PresenterAssembly.restorePermissionMode(ctx, fresh, true);
            // 会话级规则随会话生命周期（ADR-0026 决策一）：新会话无规则事件即清空
            PresenterAssembly.restorePermissionRules(ctx, fresh);
            // 换绑后的会话同样挂 tool/result 监听（旧会话随 close 清空监听器，不泄漏）
            attachFileRefs.accept(fresh);
            return buildAgent.apply(fresh);
        });
        SessionTitles.attach(session, adapter);
        // @file 指南注入（M21 工单 07）：read 在册才注册，双呈现位同源去重
        PresenterAssembly.registerFileMentionGuide(ctx, tools, prompts);
        // 补全服务交给 face（自产自用直传，不走服务声明——见上方 fileRefs 注释）
        face.setFileRefs(fileRefs);
        // tool/result 后台重建（bash/write 改文件树后索引陈旧）：初始会话与换绑共用
        // 同一监听器挂载单点（旧会话 close 清空监听器，不泄漏）
        attachFileRefs.accept(session);
        if (authToken != null) {
            System.out.println("Web 面已启动（鉴权开启）: " + webReadyUrl(face.port(), authToken));
        } else {
            System.out.println("Web 面已启动（鉴权已关闭——本机任何进程可直接访问；服务仅绑 127.0.0.1）: "
                    + webReadyUrl(face.port(), null));
        }
        // 机器锚点行（M37 工单 01，ADR-0039 决策六）：桌面壳（Electron）逐行扫 stdout
        // 认锚点拿启动 URL——壳认锚点不认人读文案，文案演进不碎壳；人读行原样保留
        System.out.println("duo:web-ready url=" + webReadyUrl(face.port(), authToken));
        return face::stop;
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface WebToolsView {

        ToolsService tools();
    }

    /**
     * 启动 URL（锚点行与人读行共用同一构造，防两处漂移）：{@code http://127.0.0.1:<port>}
     * + 鉴权开启时的 {@code ?token=} 查询段（auth: none 无查询段）。
     */
    static String webReadyUrl(int port, String authToken) {
        return "http://127.0.0.1:" + port
                + (authToken != null ? "/?token=" + authToken : "");
    }

    /**
     * 解析监听端口（M37 工单 01，ADR-0039 决策六）：三级覆盖——系统属性
     * {@code duo.web.port}（测试注入专用口）&gt; 环境变量 {@code DUO_WEB_PORT}
     * （桌面壳注入口：壳选空闲端口注入，装配文件不动）&gt; 装配 {@code config.port}
     * （缺省 8080，CLI/Web 直跑行为不变）。与 {@link DuoHome} 解析优先级同构；
     * 覆盖值空白等价未设，非法/越界回落下一级并 stdout 点名（不启动失败——壳侧
     * 起不来应归因壳，后端保持可用）。
     */
    static int resolvePort(JsonNode config) {
        return resolvePort(config,
                System.getProperty(PORT_PROP_OVERRIDE), System.getenv(PORT_ENV_OVERRIDE));
    }

    /** 端口解析的可注入形态（测试缝：JVM 进程内无法修改环境变量，DuoHome 先例）。 */
    static int resolvePort(JsonNode config, String propValue, String envValue) {
        Integer resolved = parsePortOverride(propValue, PORT_PROP_OVERRIDE);
        if (resolved == null) {
            resolved = parsePortOverride(envValue, PORT_ENV_OVERRIDE);
        }
        if (resolved != null) {
            return resolved;
        }
        return config != null && config.hasNonNull("port")
                ? config.get("port").asInt(DEFAULT_PORT) : DEFAULT_PORT;
    }

    /** 覆盖值解析：空白等价未设（null 回落）；非法/越界点名后回落（返回 null）。 */
    private static Integer parsePortOverride(String raw, String source) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            if (value >= MIN_PORT && value <= MAX_PORT) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 落到底部点名回落
        }
        System.out.println("端口覆盖无效，回落下一级: " + source + "=" + raw
                + "（合法范围 " + MIN_PORT + "-" + MAX_PORT + "，" + MIN_PORT + " 为随机分配）");
        return null;
    }

    /**
     * 解析 web 插件 config 的可选鉴权模式（{@code config.auth}，M24 工单 06）：
     * token（缺省，随机令牌鉴权）| none（显式关闭，横幅警示）；非法值 FAILED 点名。
     */
    private static String parseAuth(JsonNode config) {
        String value = config == null || !config.hasNonNull("auth")
                ? "token" : config.get("auth").asText("token").strip();
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("token") || normalized.equals("none")) {
            return normalized;
        }
        throw new PluginException("web.auth 非法: \"" + value + "\"（可选 token | none）");
    }

    /** 启动生成随机令牌（SecureRandom 24 字节 → 48 位 hex；进程生命周期一次一发，无过期）。 */
    private static String generateToken() {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    /** 权限规则服务的视图接口（方法名即服务名 permissionRules，M24 工单 02）。 */
    interface WebPermissionRulesView {

        dev.duo.harness.tools.fs.PermissionRules permissionRules();
    }

    /**
     * 「总是允许」规则生成包装（M24 工单 02，ADR-0026 决策一）：规则服务缺席原样
     * 返回；在场时拦截 allowAlways 答案生成规则，会话级经 sink 落当前会话事件。
     */
    private dev.duo.harness.tools.Answerer wrapRuleGenerating(Context ctx,
                                                              dev.duo.harness.tools.Answerer delegate) {
        dev.duo.harness.tools.fs.PermissionRules rules;
        try {
            rules = ctx.hasService(dev.duo.harness.tools.fs.PermissionRules.SERVICE_NAME)
                    ? ctx.as(WebPermissionRulesView.class).permissionRules() : null;
        } catch (Exception e) {
            rules = null;
        }
        if (rules == null) {
            return delegate;
        }
        return new dev.duo.harness.tools.fs.RuleGeneratingAnswerer(delegate, rules,
                json -> {
                    dev.duo.harness.session.Session current = face.currentSession();
                    if (current != null) {
                        current.append(dev.duo.harness.session.SessionEvent.permissionRules(json));
                    }
                });
    }

    /** prompts 服务的视图接口（方法名即服务名）。 */
    interface WebPromptsView {

        PromptRegistry prompts();
    }

    /** memory 服务的视图接口（方法名即服务名 "memory"，M25 工单 02）。 */
    interface WebMemoryView {

        dev.duo.harness.agent.memory.MemoryBook memory();
    }

    /** agentsMd 服务的视图接口（方法名即服务名 "agentsMd"，M25 工单 07）。 */
    interface WebAgentsMdView {

        dev.duo.harness.agent.prompt.AgentsMdChain agentsMd();
    }

    /** 交互服务的视图接口（方法名即服务名 "answers"）。 */
    interface WebAnswersView {

        InteractionService answers();
    }

    /** commands 服务的视图接口（方法名即服务名 "commands"）。 */
    interface WebCommandsView {

        CommandsRegistry commands();
    }

    /** 模型/思考切换登记表的视图接口（方法名即服务名 "modelSwitch"，M38 工单 01）。 */
    interface WebModelSwitchView {

        dev.duo.harness.agent.commands.ModelSwitchRegistry modelSwitch();
    }

    /**
     * Web 面切换控制器（M38 工单 01）：本类 activeConfig/swappableLlm 字段的呈现位
     * 封装——/model、/effort 升 ANY 后经登记表按发起面取用（本呈现位独立，CLI 面控
     * 制器在 CliPlugin）；切换逻辑单点在 PresenterAssembly（CLI/Web 共享）。
     */
    private final class WebModelSwitchController implements dev.duo.harness.agent.commands.ModelSwitchController {

        // synchronized：busySafe 命令不经 Web 单飞互斥，多标签并发切模型必须串行化
        // （swap 与 activeConfig 回写的读-改-写收敛，审查轴三 1）

        @Override
        public synchronized String describeModels() {
            return PresenterAssembly.describeModels(activeConfig);
        }

        @Override
        public synchronized String switchModel(String target, dev.duo.harness.session.Session session) {
            var result = PresenterAssembly.switchModel(activeConfig, swappableLlm, target, session);
            activeConfig = result.next();
            return result.message();
        }

        @Override
        public synchronized String describeEfforts() {
            return PresenterAssembly.describeEfforts(activeConfig);
        }

        @Override
        public synchronized String switchEffort(String target, dev.duo.harness.session.Session session) {
            var result = PresenterAssembly.switchEffort(activeConfig, swappableLlm, target, session);
            activeConfig = result.next();
            return result.message();
        }
    }

    /** attachments 服务的视图接口（方法名即服务名）。 */
    interface WebAttachmentsView {

        dev.duo.harness.attachment.AttachmentStore attachments();
    }

    /** session-query 服务的视图接口（方法名即服务名）。 */
    interface WebSessionQueryView {

        dev.duo.harness.sessionquery.SessionQueryService sessionQuery();
    }

    /** workspace 服务的视图接口（方法名即服务名）。 */
    interface WebWorkspaceView {

        WorkspacePolicy workspace();
    }
    /**
     * 解析 web 插件 config 的可选页长（{@code config.pageSize}，M19 还账）：首屏与每页
     * 消息数（ADR-0013 尾窗与分页同值）。缺席或 null 返回缺省 50（行为不变）；在场必须
     * 是正整数——非整数/非正一律异常点名（与 maxIterations 同规，配置错误不做静默纠正）。
     *
     * @throws PluginException 值非正整数
     */
    static int parsePageSize(JsonNode config) {
        if (config == null || !config.hasNonNull("pageSize")) {
            return WebFace.TAIL_WINDOW_MESSAGES;
        }
        JsonNode value = config.get("pageSize");
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new PluginException("pageSize 必须是整数: " + value);
        }
        int parsed = value.asInt();
        if (parsed < 1) {
            throw new PluginException("pageSize 必须为正: " + parsed);
        }
        if (parsed > MAX_PAGE_SIZE) {
            throw new PluginException("pageSize 过大（上限 " + MAX_PAGE_SIZE + "）: " + parsed
                    + "——尾窗快照按页长分配缓冲，配置错误不应演变为运行期内存耗尽");
        }
        return parsed;
    }

    /** 页长上界：尾窗快照缓冲与页长成正比，防配置错误演变为内存耗尽（OCR #21）。 */
    static final int MAX_PAGE_SIZE = 1_000;

    /** 后台任务注册表的视图接口（服务名 backgroundTasks，M23 工单 04）。 */
    interface WebBackgroundTasksView {

        dev.duo.harness.tools.fs.BackgroundTaskRegistry backgroundTasks();
    }
}
