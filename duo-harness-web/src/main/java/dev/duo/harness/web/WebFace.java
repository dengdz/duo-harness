package dev.duo.harness.web;

import com.sun.net.httpserver.HttpServer;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.attachment.AttachmentStore;
import dev.duo.harness.sessionquery.SessionQueryService;
import dev.duo.harness.tools.ToolsService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Web 双面呈现位门面（M8；M28 工单 06 拆分后为装配与生命周期门面）：JDK 内置
 * HttpServer 执行链的启动、装配参数持有与接线（会话供给/agent 重建回调/后台任务
 * 注册表/补全服务），外部契约（start/stop/port 等静态与公开成员）原位不变。
 * 各域机制拆为同包协作类：入口栅栏 {@link WebEntryGate}、标签会话域 {@link WebTabs}、
 * SSE 枢纽 {@link WebSseHub}、端点处理器 {@link WebEndpoints}、HTTP 工具 {@link WebHttp}。
 *
 * <p>只绑定 127.0.0.1（ADR-0007 v3 安全基线，鉴权 M24 工单 06）；执行器用虚拟线程
 * （每任务一线程，SSE 长连接不占平台线程，ADR-0002 同源）。</p>
 */
public final class WebFace {

    /** 协作类与测试共用的日志（包级静态——拆分后各域留门面单点引用）。 */
    static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WebFace.class);

    /**
     * fail-closed 去抖宽限（毫秒）：摘除死连接后列表暂空不立即拒——浏览器刷新的
     * "断旧立新"窗口里新连接可能尚未入列，立即拒会误杀仍有人能答的审批。
     */
    static final long FAIL_CLOSED_GRACE_MS = 2_000;

    /** 复合游标协议参数与响应字段（M26-06）。 */
    static final String PARAM_SID = "sid";
    static final String FIELD_SESSION_ID = "sessionId";

    /** 首屏尾部窗口的消息数缺省（ADR-0013 常量起步；M19 起经 web 插件 config 可配）。 */
    static final int TAIL_WINDOW_MESSAGES = 50;

    /** 匿名上下文键：无 tabId 请求的归属（单会话时代行为）。 */
    static final String DEFAULT_TAB_ID = "";

    /** 首屏/每页消息数（config.pageSize 可配，M19 还账；缺省 50 不变）。 */
    final int pageSize;

    // —— 协作域与装配状态（包私有：同包协作类按域直取）——
    final HttpServer server;
    final Context ctx;
    final ToolsService tools;
    /** 附件库（M21，可空 = 纯对话装配——附件端点 503、消息带附件 409/400）。 */
    final AttachmentStore attachments;
    /** 视觉能力闸门（llm.vision；null = 未启用）。工单 05 接线真实配置。 */
    final java.util.function.BooleanSupplier visionGate;
    /** 会话检索服务（M21 工单 08，可选依赖：null = session-query 行未装——端点 503）。 */
    final SessionQueryService sessionQuery;
    /** HITL Web answerer（审批/提问的 Web 呈现位）。 */
    final WebAnswerer webAnswerer;
    /** 上下文治理（状态面占用查询的同源数据源；null = 无治理装配，状态面省略占用）。 */
    volatile dev.duo.harness.agent.governance.ContextGovernance governance;
    /** 会话目录（侧栏列表与切换用）。 */
    final Path sessionsDir;
    /** 后台任务注册表（M23 工单 04；null = 未注入——完成通知路由不挂载）。 */
    volatile dev.duo.harness.tools.fs.BackgroundTaskRegistry backgroundTasks;

    // —— 协作域实例 ——
    final WebEntryGate gate;
    final WebSseHub hub;
    final WebTabs tabs;
    private final WebEndpoints endpoints;

    private WebFace(HttpServer server, Context ctx, ToolsService tools, Session session,
                    WebAnswerer webAnswerer, Path sessionsDir, int pageSize,
                    AttachmentStore attachments, java.util.function.BooleanSupplier visionGate,
                    SessionQueryService sessionQuery, String authToken) {
        this.pageSize = pageSize;
        this.server = server;
        this.ctx = ctx;
        this.tools = tools;
        this.attachments = attachments;
        this.visionGate = visionGate;
        this.sessionQuery = sessionQuery;
        this.webAnswerer = webAnswerer;
        this.sessionsDir = sessionsDir;
        // 入口栅栏白名单按实际绑定端口生成（端口 0 = 系统分配，测试用）
        this.gate = new WebEntryGate(server.getAddress().getPort(), authToken);
        this.hub = new WebSseHub(webAnswerer);
        this.tabs = new WebTabs(session, hub);
        this.endpoints = new WebEndpoints(this);
    }

    /**
     * 启动并绑定 127.0.0.1:port（port 0 = 系统随机分配，测试用）。
     * governance 可为 null（无治理装配时状态面省略上下文占用字段）。
     *
     * <p><b>会话所有权</b>：WebFace 接管传入会话的生命周期——传入会话归匿名上下文
     * （无 tabId 请求）；带 tabId 的浏览器标签各自懒创建会话（新标签默认新建），
     * 换绑（/new、/switch）时关闭该标签旧会话释放其独占锁，{@link #stop()} 关闭
     * 全部标签会话。调用方无须（也不应）再关闭。</p>
     *
     * @throws IOException 端口绑定失败
     */
    public static WebFace start(int port, Context ctx, ToolsService tools, Session session,
                                ChatAgent agent, dev.duo.harness.agent.governance.ContextGovernance governance,
                                WebAnswerer webAnswerer, Path sessionsDir)
            throws IOException {
        return start(port, ctx, tools, session, agent, governance, webAnswerer, sessionsDir,
                TAIL_WINDOW_MESSAGES, null, null);
    }

    /**
     * 启动（页长可配版，M19 还账）：{@code pageSize} 为首屏与每页消息数（ADR-0013
     * 尾窗与分页同值语义不变），须为正——由 WebPlugin 的 config 解析把关。
     */
    public static WebFace start(int port, Context ctx, ToolsService tools, Session session,
                                ChatAgent agent, dev.duo.harness.agent.governance.ContextGovernance governance,
                                WebAnswerer webAnswerer, Path sessionsDir, int pageSize)
            throws IOException {
        return start(port, ctx, tools, session, agent, governance, webAnswerer, sessionsDir,
                pageSize, null, null);
    }

    /**
     * 启动（M21 附件版）：{@code attachments} 为附件库（可空 = 纯对话装配——附件
     * 端点 503、消息带附件 409/400）；{@code visionGate} 为视觉能力闸门（可空 =
     * 未启用——Web 收图即拒、read_image 执行前即拒；工单 05 接线 llm.vision）。
     */
    public static WebFace start(int port, Context ctx, ToolsService tools, Session session,
                                ChatAgent agent, dev.duo.harness.agent.governance.ContextGovernance governance,
                                WebAnswerer webAnswerer, Path sessionsDir, int pageSize,
                                AttachmentStore attachments, java.util.function.BooleanSupplier visionGate)
            throws IOException {
        return start(port, ctx, tools, session, agent, governance, webAnswerer, sessionsDir,
                pageSize, attachments, visionGate, null);
    }

    /**
     * 启动（M21 工单 08 会话检索版）：{@code sessionQuery} 为检索服务（可空 =
     * session-query 行未装——{@code /api/search} 端点 503，前端搜索框给出提示）。
     */
    public static WebFace start(int port, Context ctx, ToolsService tools, Session session,
                                ChatAgent agent, dev.duo.harness.agent.governance.ContextGovernance governance,
                                WebAnswerer webAnswerer, Path sessionsDir, int pageSize,
                                AttachmentStore attachments, java.util.function.BooleanSupplier visionGate,
                                SessionQueryService sessionQuery)
            throws IOException {
        return start(port, ctx, tools, session, agent, governance, webAnswerer, sessionsDir,
                pageSize, attachments, visionGate, sessionQuery, null);
    }

    /**
     * 启动（M24 工单 06 鉴权版）：{@code authToken} 非 null 时开启鉴权令牌——全端点
     * （含静态资源）校验 {@code X-Duo-Token} 头或 {@code ?token=} 查询参数，失败一律
     * 403（fail-closed）；null = 鉴权关闭（测试与嵌入用途；产品装配 WebPlugin 恒传
     * 启动生成的随机令牌，yml 可显式关闭并横幅警示）。
     */
    public static WebFace start(int port, Context ctx, ToolsService tools, Session session,
                                ChatAgent agent, dev.duo.harness.agent.governance.ContextGovernance governance,
                                WebAnswerer webAnswerer, Path sessionsDir, int pageSize,
                                AttachmentStore attachments, java.util.function.BooleanSupplier visionGate,
                                SessionQueryService sessionQuery, String authToken)
            throws IOException {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(agent, "agent");
        // webAnswerer 可为 null（骨架用例不测 HITL）；non-null 时必有 sessionsDir
        if (webAnswerer != null) {
            Objects.requireNonNull(sessionsDir, "sessionsDir");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        WebFace face = new WebFace(server, ctx, tools, session, webAnswerer, sessionsDir, pageSize,
                attachments, visionGate, sessionQuery, authToken);
        face.governance = governance;
        face.tabs.bindTab(face.tabs.defaultTab(), session);
        face.tabs.defaultTab().agent = agent;
        if (webAnswerer != null) {
            // 挂起项标签归属（M24 工单 07）：ask 时刻解析 turn 上下文——fail-closed 按标签选择性拒绝
            webAnswerer.setTabResolver(WebTabs::currentTabId);
        }
        face.endpoints.register(server);
        face.hub.startHeartbeat();
        server.start();
        return face;
    }

    /** 注入后台任务注册表（完成通知路由；可选——fs 工具未装的部署无通知）。 */
    public void setBackgroundTaskRegistry(dev.duo.harness.tools.fs.BackgroundTaskRegistry registry) {
        this.backgroundTasks = registry;
        if (registry != null) {
            // 完成通知路由（M23 工单 04，ADR-0025 决策二）：与 CLI 同款双路径——
            // 空闲直接开新 turn 消费（虚拟线程），busy 挂收件箱 next-turn 收口合并
            registry.addListener(task -> {
                // 归属过滤（M23 工单 06 验收修正）：Web 只消费本位发起（或无归属）的
                // 任务——CLI 侧任务完成不在浏览器开轮/注入
                if (task.owner() != null && !ChatAgent.PRESENTER_WEB.equals(task.owner())) {
                    return;
                }
                // 标签归属（M24 工单 07 记档）：任务 owner 只有呈现位粒度无 tab——
                // 确定性路由到创建最早的真实标签（匿名上下文不参与——它是无 tabId
                // 请求的兼容位，路由进去浏览器标签永远看不到）；无真实标签回退匿名
                TabContext target = tabs.all().stream()
                        .filter(t -> !DEFAULT_TAB_ID.equals(t.tabId))
                        .min(java.util.Comparator.comparingLong(t -> t.seq))
                        .orElse(tabs.defaultTab());
                ChatAgent current = target.agent;
                if (current == null) return;
                String notice = task.notice();
                if (target.busy.compareAndSet(false, true)) {
                    startAgentTurn(notice, target);
                } else {
                    current.injectNextTurn(notice);
                }
            });
        }
    }

    /**
     * 启动一轮 agent 执行（虚拟线程）+ next-turn 排干循环（M23 工单 04）：通知路由与
     * 用户消息共用——turn 收口后 next-turn 队列非空则合并续跑，直到队列空。
     * 执行期以 turn 上下文（ITL）标记所属标签——WebPlugin 经 {@code face::currentSession}
     * 共享的会话供给方（审计桥/规则 sink/todo/subagent）由此解析到发起标签的会话。
     */
    void startAgentTurn(String text, TabContext tab) {
        tab.agentRunning.set(true);
        Thread.ofVirtual().start(() -> {
            TabContext.TURN_TAB.set(tab.tabId);
            String currentText = text;
            try {
                while (currentText != null) {
                    dev.duo.harness.agent.AgentReply reply =
                            tab.agent.send(currentText, new dev.duo.harness.agent.AgentListener() {
                                @Override
                                public void onChunk(String chunk) {
                                    tab.session.append(SessionEvent.assistantChunk(chunk));
                                }
                            });
                    if (!reply.completed()) {
                        // 迭代上限等异常终止（ADR-0018）：直推 run/error 错误卡补齐可见性
                        // （BUG-20260917-03 同口径）。中断收口除外——用户主动停止不是执行
                        // 异常，可见性由 assistant/interrupted 会话事件承载（前端中性标记、
                        // 回放投影 [已中断] 前缀），弹错误卡是语义错位（验收实测反馈）
                        if (!reply.interrupted()) {
                            hub.pushTransientFrame(tab, WebHttp.toJson(SessionEvent.errorEvent(reply.finalText())));
                        }
                    }
                    java.util.List<String> queued = tab.agent.drainNextTurn();
                    currentText = queued.isEmpty() ? null : String.join("\n\n", queued);
                }
            } catch (Exception e) {
                log.warn("消息处理失败", e);
                hub.pushTransientFrame(tab, WebHttp.toJson(SessionEvent.errorEvent("消息处理失败，详情见服务端日志")));
            } finally {
                TabContext.TURN_TAB.remove();
                tab.agentRunning.set(false);
                tab.busy.set(false);
            }
        });
    }

    /** 当前 turn 的标签 id（静态转发；装配层接线取 {@link WebTabs#currentTabId}）。 */
    static String currentTabId() {
        return WebTabs.currentTabId();
    }

    /** 注册会话变更回调（装配层接线）：换绑后按会话重建 agent 并**返回**——多标签下
     * agent 归标签上下文而非全局单槽（Consumer 时代的 setAgent 全局写入即分脑）。 */
    void onSessionChanged(java.util.function.Function<Session, ChatAgent> onChanged) {
        tabs.onSessionChanged(onChanged);
    }

    /** 注册 /new 的供给者（装配层接线；供 WebPlugin 调用，包级可见）。 */
    void onNewSession(java.util.function.Supplier<Session> supplier) {
        tabs.onNewSession(supplier);
    }

    /** 测试与装配层用：替换匿名上下文的对话执行者（start 初始接线；标签 agent 走回调）。 */
    void setAgent(ChatAgent agent) {
        tabs.setDefaultAgent(agent);
    }

    /**
     * 当前会话：turn 上下文内 = 发起标签的会话（WebPlugin 共享供给方——审计桥/规则
     * sink/todo/subagent——由此落到正确标签的会话）；turn 外 = 匿名上下文会话
     * （启动接线与测试的既有语义）。
     */
    Session currentSession() {
        return tabs.currentSession();
    }

    /** 摘除死连接（测试驱动摘除时点的入口；机制在 {@link WebSseHub#removeClient}）。 */
    void removeClient(SseClient client) {
        hub.removeClient(client);
    }

    /** 鉴权令牌访问器（WebPlugin 打印带 token 的 URL 用；关闭时 null）。 */
    public String authToken() {
        return gate.authToken();
    }

    /** 实际绑定端口（构造传 0 时为系统分配值）。 */
    public int port() {
        return server.getAddress().getPort();
    }

    /** 当前活跃的 SSE 连接数（观测用）。 */
    public int sseConnections() {
        return hub.connectionCount();
    }

    /** 停止服务与心跳（插件 dispose 调用），并关闭全部标签会话（会话所有权见 {@link #start}）；
     * 悬空审批全局 fail-closed 收口（停机即无人能答，别让 turn 线程空等 10 分钟兜底）。 */
    public void stop() {
        hub.shutdown();
        server.stop(0);
        tabs.closeAll();
    }

    /** SSE 客户端连接：输出流 + 帧写串行化 + 所属标签（会话事件按标签路由的依据）。
     * 回放（连接线程）与实时广播（写线程）会并发写同一连接，不加锁则帧字节交错、
     * 前端解析失败。 */
    static final class SseClient {

        private final OutputStream out;
        /** 连接所属标签（握手时上报；匿名连接为 {@link WebFace#DEFAULT_TAB_ID}）。 */
        final String tabId;

        SseClient(OutputStream out, String tabId) {
            this.out = out;
            this.tabId = tabId;
        }

        /** 单帧原子写（含 flush）。 */
        synchronized void send(String frame) throws IOException {
            out.write(frame.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        synchronized void close() {
            try {
                out.close();
            } catch (IOException ignored) {
                // 连接已死
            }
        }
    }
}
