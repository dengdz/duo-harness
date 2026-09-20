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
     * workspace 为可选依赖（M20 验收实测补齐，ADR-0019）：权限档恢复
     * （PresenterAssembly.restorePermissionMode）与双面 /permission 命令经本插件
     * Context 惰性解析 workspace——未声明时内核"错误前移"拒绝读取，Web 侧恢复被
     * 跳过、浏览器切档不可用。缺席（纯对话 Web 装配）视为无档位语义，照常启动。
     */
    @Override
    public Set<String> optionalInject() {
        return Set.of(dev.duo.harness.tools.fs.WorkspacePolicy.SERVICE_NAME);
    }

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        int port = config != null && config.hasNonNull("port")
                ? config.get("port").asInt(DEFAULT_PORT) : DEFAULT_PORT;
        ToolsService tools = ctx.as(WebToolsView.class).tools();
        PromptRegistry prompts = ctx.as(WebPromptsView.class).prompts();
        InteractionService answers = ctx.as(WebAnswersView.class).answers();
        CommandsRegistry commands = ctx.as(WebCommandsView.class).commands();

        // 执行链装配（呈现位共享单点，ADR-0011）：LLM 配置 → 重试 adapter；
        // LLM 未配置 → 插件 FAILED 点名
        LlmConfig llm = LlmConfig.load();
        var adapter = PresenterAssembly.llmAdapter(llm);

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
                ChatAgent.PRESENTER_WEB);
        // HITL Web answerer：注册进交互 seam（断连 fail-closed 由 WebFace 联动）
        WebAnswerer webAnswerer = new WebAnswerer(10 * 60 * 1000L);

        // 页长解析在 start 之前——PluginException 不入下方 IOException catch，
        // 确保配置错误路径也走 session.close() 释放独占锁（OCR #17）
        int pageSize = parsePageSize(config);
        try {
            face = WebFace.start(port, ctx, tools, session, agent, governance, webAnswerer,
                    DuoHome.resolve().resolveDir("agent-sessions"), pageSize);
        } catch (java.io.IOException e) {
            session.close(); // 启动失败即释放会话独占锁：不给失败的启动留占用
            throw new PluginException("Web 服务启动失败（端口 " + port + "）", e);
        }
        // 审计桥包装（ADR-0008 决策 5）：approval/requested、approval/decided 事件落会话
        // ——会话监听器推 SSE，页面据此渲染审批卡；会话经 face 延迟解析（/new 换绑后留新会话）
        answers.register(ctx, new AuditingAnswerer(face::currentSession, webAnswerer));
        // /compact（M19，ADR-0020 决策 6）：双面命令随 Web 装配注册（查重先到先得——
        // CLI 已注册则跳过），会话经 face 延迟解析取当前值
        PresenterAssembly.registerCompactCommand(ctx, commands, governance);
        PresenterAssembly.registerTitleCommand(ctx, commands);
        // 权限档持久化（M19，ADR-0020 决策 10）：启动续接只恢复不重置（BUG-20260919-03
        // ——双开下另一呈现位可能刚恢复过档位）；换绑恢复在 onSessionChanged 回调里执行
        PresenterAssembly.restorePermissionMode(ctx, session, false);
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
                    ChatAgent.PRESENTER_WEB));
            SessionTitles.attach(fresh, adapter);
            // 显式换绑（新话题/切换）：无切档记录即重置回 yml 缺省（ADR-0020 决策 10）
            PresenterAssembly.restorePermissionMode(ctx, fresh, true);
        });
        SessionTitles.attach(session, adapter);
        System.out.println("Web 面已启动: http://127.0.0.1:" + face.port());
        return face::stop;
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface WebToolsView {

        ToolsService tools();
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
}
