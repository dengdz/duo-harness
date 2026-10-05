package dev.duo.harness.web;

import com.sun.net.httpserver.HttpExchange;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 标签会话域（M28 工单 06 从 WebFace 拆出）：每浏览器标签一份会话绑定（session/agent/
 * busy/订阅），标签解析与懒创建、换绑与 agent 重建回调、turn 上下文——多会话并存的
 * 全部状态机单点（M24 工单 07，ADR-0026 决策五延伸）。
 */
final class WebTabs {

    private static final Logger log = LoggerFactory.getLogger(WebTabs.class);

    /** 标签 id 白名单（浏览器 crypto.randomUUID 或降级串；预编译——每请求都校验）。 */
    private static final java.util.regex.Pattern TAB_ID =
            java.util.regex.Pattern.compile("[0-9a-zA-Z_-]{1,64}");

    /** 标签上下文表：key 为标签上报的 tabId；缺失头视为匿名上下文（WebFace.DEFAULT_TAB_ID）。 */
    private final ConcurrentHashMap<String, TabContext> tabs = new ConcurrentHashMap<>();
    /** 标签创建序号发生器。 */
    private final AtomicLong tabSeq = new AtomicLong();
    /** 换绑串行化锁：并发切换（连点侧栏/新话题/新标签首请求）下保证"关闭上一个"链条线性、会话锁不泄漏。 */
    private final Object bindLock = new Object();
    /** 匿名上下文（start 传入的初始 session；无 tabId 请求的归属）。 */
    private final TabContext defaultTab;
    /** 新会话供给者（/new 每次给全新会话；入参 = 会话工作区，null = 进程 cwd 兜底——
     *  M38 工单 07：创建时可选工作区，懒创建/无参 /new 走 null）。 */
    private volatile Function<Path, Session> newSessionSupplier =
            ws -> { throw new IllegalStateException("新会话供给者未装配"); };
    /**
     * 会话变更回调（/new 与 /switch 与新标签创建共用）：装配层按入参会话重建 agent 并
     * **返回**——ToolCallingAgent 持有 final 会话引用，不重建即分脑（消息落旧会话、
     * 页面显示新会话，BUG-20260914-02）；返回值归标签上下文（M24 工单 07）。
     */
    private volatile Function<Session, ChatAgent> sessionChangedCallback =
            changed -> null;
    /** SSE 枢纽（bindTab 的事件订阅推送源）。 */
    private final WebSseHub sse;

    WebTabs(Session initialSession, WebSseHub sse) {
        this.sse = sse;
        // 匿名上下文（M24 工单 07）：start 传入的初始会话归默认 tab——
        // 带 tabId 的浏览器标签各自懒创建上下文，无 tabId 请求（curl/缓存页/测试）走这里
        this.defaultTab = new TabContext(WebFace.DEFAULT_TAB_ID, tabSeq.getAndIncrement(), initialSession);
        this.tabs.put(WebFace.DEFAULT_TAB_ID, this.defaultTab);
    }

    /** 匿名上下文（start 初始接线用）。 */
    TabContext defaultTab() {
        return defaultTab;
    }

    /** 按 id 取标签（后台任务通知路由用；可 null）。 */
    TabContext get(String tabId) {
        return tabs.get(tabId);
    }

    /** 全部在册标签（stop 收口遍历用）。 */
    java.util.List<TabContext> all() {
        return java.util.List.copyOf(tabs.values());
    }

    /** 当前 turn 的标签 id（turn 上下文外为 null——WebAnswerer 归属解析用）。 */
    static String currentTabId() {
        return TabContext.currentTabId();
    }

    /** 匿名上下文的对话执行者（start 初始接线与测试用；标签 agent 走回调）。 */
    void setDefaultAgent(ChatAgent agent) {
        this.defaultTab.agent = agent;
    }

    /** 注册会话变更回调（装配层接线）：换绑后按会话重建 agent 并**返回**——多标签下
     * agent 归标签上下文而非全局单槽（Consumer 时代的 setAgent 全局写入即分脑）。 */
    void onSessionChanged(Function<Session, ChatAgent> onChanged) {
        this.sessionChangedCallback = java.util.Objects.requireNonNull(onChanged, "onChanged");
    }

    /** 注册 /new 的供给者（装配层接线；入参 = 会话工作区，null = 进程 cwd 兜底）。 */
    void onNewSession(Function<Path, Session> supplier) {
        this.newSessionSupplier = java.util.Objects.requireNonNull(supplier, "supplier");
    }

    /**
     * 当前会话：turn 上下文内 = 发起标签的会话（WebPlugin 共享供给方——审计桥/规则
     * sink/todo/subagent——由此落到正确标签的会话）；turn 外 = 匿名上下文会话
     * （启动接线与测试的既有语义）。
     */
    Session currentSession() {
        String turnTab = TabContext.TURN_TAB.get();
        if (turnTab != null) {
            TabContext tab = tabs.get(turnTab);
            if (tab != null && tab.session != null) {
                return tab.session;
            }
        }
        return defaultTab.session;
    }

    /**
     * 标签解析（M24 工单 07）：X-Tab-Id 头优先，SSE 通道回退 {@code ?tabId=} 查询串
     * （EventSource 不支持自定义头，与 token 双通道同口径）。缺失 = 匿名上下文；携带
     * 但形态非法（白名单外字符/超长）400 拒绝——坏值静默并入匿名上下文会让多标签
     * 互踩复活（fail-closed）。命中未知 tabId 即懒创建：新会话 + 回调重建 agent
     * （新标签默认新建会话；服务端重启后旧标签的 tabId 同样无记录，等同新标签）。
     *
     * @return 上下文；null = 请求已响应（非法 tabId / 创建失败）
     */
    TabContext resolveTab(HttpExchange exchange) throws IOException {
        String tabId = exchange.getRequestHeaders().getFirst("X-Tab-Id");
        if (tabId == null || tabId.isBlank()) {
            tabId = WebHttp.queryParam(exchange, "tabId");
        }
        tabId = tabId == null ? "" : tabId.strip();
        if (!tabId.isEmpty() && !TAB_ID.matcher(tabId).matches()) {
            WebHttp.respondEmpty(exchange, 400);
            return null;
        }
        TabContext tab = tabs.get(tabId);
        if (tab != null) {
            return tab;
        }
        // 慢路径整体持锁（含回调建 agent）：同 tabId 的并发首请求（浏览器首载同时发
        // status/sessions/SSE 三发）要么等创建完成拿到带 agent 的完整上下文，要么复用
        // ——agent 挪到锁外回填会留出"上下文在场而 agent 未就位"窗口，早到消息 503
        synchronized (bindLock) {
            tab = tabs.get(tabId); // 双检：并发首请求只建一个会话（会话文件与锁各就各位）
            if (tab != null) {
                return tab;
            }
            Session fresh = null;
            try {
                fresh = newSessionSupplier.apply(null); // 懒创建不带工作区参数：进程 cwd 兜底
                tab = new TabContext(tabId, tabSeq.getAndIncrement(), fresh);
                bindTab(tab, fresh);
                tab.agent = sessionChangedCallback.apply(fresh);
                tabs.put(tabId, tab);
                return tab;
            } catch (Exception e) {
                if (fresh != null) {
                    // 回调失败（建 agent/挂标题）时新会话已持独占锁：就地释放，
                    // 不给失败的首请求留永久占用（同 bindTab 的锁泄漏自警）
                    fresh.close();
                }
                log.warn("标签上下文创建失败 tabId={}", tabId, e);
                WebHttp.respondText(exchange, 500, "标签会话创建失败");
                return null;
            }
        }
    }

    /** 绑定会话到标签（事件监听 SSE 推送源）；换会话时先解绑旧的，并释放旧会话的独占锁。 */
    void bindTab(TabContext tab, Session target) {
        synchronized (bindLock) {
            Session previous = tab.session;
            tab.session = target;
            if (tab.sseSubscription != null) {
                try {
                    tab.sseSubscription.dispose();
                } catch (Exception e) {
                    // 旧监听器注销失败无碍：新订阅已就位
                }
            }
            // 回调闭包捕获 target 而非读 tab.session：换绑窗口内旧会话的在途事件
            // 仍以旧会话 id 作帧前缀（序号与 id 同源，杜绝「新 sid + 旧序号」错配帧）
            tab.sseSubscription = target.addListener((index, event) ->
                    sse.pushSessionEvent(index, event, target, tab.tabId));
            if (previous != null && previous != target) {
                // 换绑即本标签不再使用旧会话：释放独占锁（否则旧会话被本进程白占，他处打不开）。
                // 必须串行：并发换绑各关各的快照会跳过中间会话，其独占锁永久泄漏
                previous.close();
            }
        }
    }

    /** 换绑标签会话并经回调重建该标签的 agent（/new 语义；回调返回值即新 agent）。
     *  无参形态 = 进程 cwd 兜底（CLI 直跑兼容、既有调用面零变化）。 */
    void newSessionFor(TabContext tab) {
        newSessionFor(tab, null);
    }

    /** 同上，带会话工作区（M38 工单 07）：null 回落进程 cwd；路径合法性由端点校验后传入。 */
    void newSessionFor(TabContext tab, java.nio.file.Path workspace) {
        Session fresh = newSessionSupplier.apply(workspace);
        bindTab(tab, fresh);
        tab.agent = sessionChangedCallback.apply(fresh);
    }

    /** 换绑到已加载的会话（/switch 语义；agent 重建回调同款）。 */
    void rebind(TabContext tab, Session loaded) {
        bindTab(tab, loaded);
        tab.agent = sessionChangedCallback.apply(loaded);
    }

    /** 停机收口（WebFace.stop 调用）：关闭全部标签会话（会话所有权见 WebFace.start）。 */
    void closeAll() {
        for (TabContext tab : all()) {
            if (tab.session != null) {
                tab.session.close();
            }
        }
        tabs.clear();
    }
}
