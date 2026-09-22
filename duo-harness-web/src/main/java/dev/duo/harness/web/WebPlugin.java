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
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.llm.LlmConfig;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.commands.CommandsRegistry;
import dev.duo.harness.agent.governance.ContextGovernance;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolsService;
import dev.duo.harness.tools.fs.WorkspacePolicy;

import java.util.Set;

/**
 * Web 双面插件（M8 起，M10 加固）：Boot yml 一行启用本地 Web 服务——静态单页
 * （对话/状态双区）、`/api/status` 状态 JSON（含上下文占用）、`/api/events` SSE
 * 会话事件流（首连快照 / 重连游标增量，ADR-0010）、`/api/message` 对话入口、
 * `/api/session/*` 会话列换与切换（切换有独占锁语义）。只绑 127.0.0.1，
 * 无鉴权（本地个人工具场景，鉴权按需再加）。
 *
 * <p>inject tools + prompts + answers：状态面与对话面的三个数据源（标准服务注入
 * 模式）。技能清单 / AGENTS.md 片段由对应插件（SkillsPlugin / AgentsMdPlugin）
 * 注册进 prompts 服务——本插件只做对话执行者装配，不重复注册。装配链还负责：
 * 注册 ask_user 与计划呈交工具（纯 Web 部署的 HITL 完整，与 CLI 装配共存时先到先得）、
 * 装配 Web answerer 与审计桥、接管会话独占锁（启动遇占用即 FAILED 点名被占会话）。</p>
 *
 * <p>LLM 未配置时插件 FAILED 点名（整个 Web 面不可用——LLM 配置先于服务启动装载）。</p>
 *
 * <p>配置（块内字段可省）：</p>
 * <pre>{@code config:
 *   port: 8080        # 监听端口（省略默认 8080；只绑 127.0.0.1）
 *   maxIterations: 30 # 单轮对话迭代上限（省略默认 10；计划模式等探索型任务建议调高）
 *   maxParallelToolCalls: 10 # 单轮并发安全工具并行上限（省略默认 10；=1 即完全串行，排障用）
 *   pipelineTimeoutMs: 120000 # 工具执行管线缺省超时毫秒（省略默认 120s）
 *   governance: {}    # 上下文治理阈值段（省略即缺省常量）}</pre>
 */
public final class WebPlugin implements Plugin<JsonNode> {

    /** 默认监听端口。 */
    public static final int DEFAULT_PORT = 8080;

    private WebFace face;

    @Override
    public Set<String> inject() {
        return Set.of(ToolsService.SERVICE_NAME, PromptRegistry.SERVICE_NAME,
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
                dev.duo.harness.tools.fs.PermissionRules.SERVICE_NAME);
    }

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        int port = config != null && config.hasNonNull("port")
                ? config.get("port").asInt(DEFAULT_PORT) : DEFAULT_PORT;
        // 鉴权令牌（M24 工单 06，ADR-0026 决策五）：缺省生成随机令牌（fail-closed），
        // web.auth: none 显式关闭（启动横幅警示）；非法值 FAILED 点名
        String authMode = parseAuth(config);
        String authToken = "token".equals(authMode) ? generateToken() : null;
        ToolsService tools = ctx.as(WebToolsView.class).tools();
        PromptRegistry prompts = ctx.as(WebPromptsView.class).prompts();
        InteractionService answers = ctx.as(WebAnswersView.class).answers();
        CommandsRegistry commands = ctx.as(WebCommandsView.class).commands();

        // 执行链装配（呈现位共享单点，ADR-0011）：LLM 配置 → 重试 adapter；
        // LLM 未配置 → 插件 FAILED 点名
        LlmConfig llm = LlmConfig.load();
        var adapter = PresenterAssembly.llmAdapter(llm);
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
        // 索引以 workspace 根为界。本插件自产自用（补全端点直取），**不进
        // optionalInject 声明**：自产自依赖会让内核 recheck 循环重跑 apply
        dev.duo.harness.agent.fileref.FileReferenceService fileRefs =
                ctx.hasService(WorkspacePolicy.SERVICE_NAME)
                        ? new dev.duo.harness.agent.fileref.FileReferenceService(
                                ctx.as(WebWorkspaceView.class).workspace().root())
                        : null;

        Session session;
        try {
            session = Session.latest(DuoHome.resolve().resolveDir("agent-sessions"));
            if (session == null) {
                session = Session.create(DuoHome.resolve().resolveDir("agent-sessions"));
            }
        } catch (dev.duo.harness.session.SessionLockedException e) {
            // 单写者检测：会话已被占用（另一入口或其他进程）——启动即失败并点名会话，
            // 不静默分脑共享同一日志（两个进程各持内存视图、JSONL 交错追加）
            throw new PluginException("Web 面无法启动：" + e.getMessage(), e);
        }
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
        ChatAgent agent = PresenterAssembly.chatAgent(
                adapter, tools, session, prompts, maxIterations, maxParallelToolCalls, governance,
                ChatAgent.PRESENTER_WEB, variants, llm.vision(), fileDelivery);
        // HITL Web answerer：注册进交互 seam（断连 fail-closed 由 WebFace 联动）
        WebAnswerer webAnswerer = new WebAnswerer(10 * 60 * 1000L);

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
                ctx, tools, answers, ChatAgent.PRESENTER_WEB, face::currentSession, () -> { });
        // todo 分解抓手（ADR-0018）：呈现状态工具随装配注册（与交互工具同供给模式）
        PresenterAssembly.registerTodoWriteTool(ctx, tools, face::currentSession);
        // subagent 宿主发布（M15，ADR-0015）：发布父侧执行链构件——SubagentPlugin
        // 在场且配置了模板时自行装配五件工具；未配置部署零感知（只发服务，零工具）
        PresenterAssembly.publishSubagentHost(ctx, adapter, governanceTuning, face::currentSession);
        // /new：全新会话；/switch：换绑既有会话——两者换绑后都经会话变更回调重建 agent
        // （ToolCallingAgent 持有 final 会话引用，不重建即分脑）
        face.onNewSession(() -> Session.create(DuoHome.resolve().resolveDir("agent-sessions")));
        // 会话变更回调是单回调槽（覆盖式 setter，非多播）——全部换绑动作必须合并在这一次
        // 注册里。教训（BUG-20260916-01）：第二处注册会覆盖"换绑重建 agent"，切回分脑
        //（agent 写已 close 的旧会话，发消息必报错）。标题生成（工单 M13-06）随换绑同源
        // attach，双开时与 CLI 共享静态去重表
        face.onSessionChanged(fresh -> {
            face.setAgent(PresenterAssembly.chatAgent(
                    adapter, tools, fresh, prompts, maxIterations, maxParallelToolCalls, governance,
                    ChatAgent.PRESENTER_WEB, variants, llm.vision(), fileDelivery));
            SessionTitles.attach(fresh, adapter);
            // 显式换绑（新话题/切换）：无切档记录即重置回 yml 缺省（ADR-0020 决策 10）
            PresenterAssembly.restorePermissionMode(ctx, fresh, true);
            // 会话级规则随会话生命周期（ADR-0026 决策一）：新会话无规则事件即清空
            PresenterAssembly.restorePermissionRules(ctx, fresh);
            // 换绑后的会话同样挂 tool/result 监听（旧会话随 close 清空监听器，不泄漏）
            if (fileRefs != null) {
                fresh.addListener((index, event) -> {
                    if (dev.duo.harness.session.SessionEvent.TOOL_RESULT.equals(event.type())) {
                        fileRefs.markStale();
                    }
                });
            }
        });
        SessionTitles.attach(session, adapter);
        // @file 指南注入（M21 工单 07）：read 在册才注册，双呈现位同源去重
        PresenterAssembly.registerFileMentionGuide(ctx, tools, prompts);
        // 补全服务交给 face（自产自用直传，不走服务声明——见上方 fileRefs 注释）
        face.setFileRefs(fileRefs);
        // tool/result 后台重建（bash/write 改文件树后索引陈旧）：初始会话监听——
        // 换绑在 onSessionChanged 回调里重挂；旧会话 close 清空监听器，不泄漏
        if (fileRefs != null) {
            session.addListener((index, event) -> {
                if (dev.duo.harness.session.SessionEvent.TOOL_RESULT.equals(event.type())) {
                    fileRefs.markStale();
                }
            });
        }
        if (authToken != null) {
            System.out.println("Web 面已启动（鉴权开启）: http://127.0.0.1:" + face.port()
                    + "/?token=" + authToken);
        } else {
            System.out.println("Web 面已启动（鉴权已关闭——本机任何进程可直接访问；服务仅绑 127.0.0.1）: http://127.0.0.1:" + face.port());
        }
        return face::stop;
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface WebToolsView {

        ToolsService tools();
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

    /** 交互服务的视图接口（方法名即服务名 "answers"）。 */
    interface WebAnswersView {

        InteractionService answers();
    }

    /** commands 服务的视图接口（方法名即服务名 "commands"）。 */
    interface WebCommandsView {

        CommandsRegistry commands();
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
