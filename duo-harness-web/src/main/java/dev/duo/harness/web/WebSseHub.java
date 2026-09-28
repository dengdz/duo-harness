package dev.duo.harness.web;

import com.sun.net.httpserver.HttpExchange;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * SSE 广播枢纽（M28 工单 06 从 WebFace 拆出）：客户端连接表、心跳保活与死连接摘除、
 * 按标签路由的广播、断线重连的复合游标回放窗口、悬空交互 fail-closed 的去抖宽限——
 * 事件出站的全部机制单点。SSE 连接带 15s 心跳帧保活（写失败即摘除死连接，兼防代理
 * 静默断连）；全部客户端断开且宽限期内无新连接入列时悬空交互 fail-closed（经
 * {@link WebAnswerer}，ADR-0008 / ADR-0010 延伸——判定语义是"是否仍有人能看见该审批"，
 * 刷新断旧立新不误杀）。
 */
final class WebSseHub {

    private static final Logger log = LoggerFactory.getLogger(WebSseHub.class);

    private static final long HEARTBEAT_INTERVAL_MS = 15_000;
    /** SSE 游标请求头（浏览器重连自动携带，值为最后收到的 id）。 */
    private static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";

    /** 复合游标分隔符（M26-06）：帧 id「会话id#序号」——编码（dataFrameWithId）与
     * 解析（resolveReplayWindow）共用，协议级单一事实源。 */
    private static final String CURSOR_SEP = "#";
    /** 复合游标协议响应字段（M26-06）。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    /** SSE 客户端连接（多客户端广播，心跳写失败即摘除）。 */
    private final CopyOnWriteArrayList<WebFace.SseClient> sseOutputs = new CopyOnWriteArrayList<>();
    /** 心跳调度器（保活 + 死连接摘除）。 */
    private final java.util.concurrent.ScheduledExecutorService heartbeat =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "web-sse-heartbeat");
                t.setDaemon(true);
                return t;
            });
    /** 每标签的去抖复查任务（同一标签同一时刻至多一个；窗口内新摘除会重置窗口）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<?>>
            failClosedChecks = new java.util.concurrent.ConcurrentHashMap<>();
    /** HITL Web answerer（可 null = 骨架装配——fail-closed 摘除只清连接不拒挂起项）。 */
    private final WebAnswerer webAnswerer;

    WebSseHub(WebAnswerer webAnswerer) {
        this.webAnswerer = webAnswerer;
    }

    /** 启动心跳调度（start 收口调用）。 */
    void startHeartbeat() {
        heartbeat.scheduleAtFixedRate(this::pingAll,
                HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** 会话事件广播（按标签路由，M24 工单 07）：帧带复合游标 id（会话id#日志序号，M26-06）
     * ——浏览器以最后收到的 id 作重连游标（ADR-0010）；只有绑定该会话的标签连接能收到（A 标签的卡片不弹到 B）。
     * source 为注册监听时捕获的会话（不随换绑漂移，见 WebTabs.bindTab）。 */
    void pushSessionEvent(int index, SessionEvent event, Session source, String tabId) {
        // 序号由会话在写入处随回调给出（不从日志末尾反推——并发追加下反推会错位）
        String frame = WebHttp.toJson(event);
        broadcast(client -> client.send(dataFrameWithId(source.id(), index, frame)), tabId);
    }

    /** 非会话帧广播（run/error 等直推帧）：帧不带序号，契约见 {@link #dataFrame}；按发起标签路由。 */
    void pushTransientFrame(TabContext tab, String payload) {
        broadcast(client -> client.send(dataFrame(payload)), tab.tabId);
    }

    /** 帧写动作（写失败 IOException 即摘除该连接）。 */
    @FunctionalInterface
    private interface FrameSink {

        void write(WebFace.SseClient client) throws IOException;
    }

    /** 广播到标签 ownTabId 的连接（M24 工单 07 路由核心）：逐连接执行帧写，写失败即摘除死连接。 */
    private void broadcast(FrameSink sink, String ownTabId) {
        for (WebFace.SseClient client : sseOutputs.toArray(WebFace.SseClient[]::new)) {
            if (!ownTabId.equals(client.tabId)) {
                continue;
            }
            try {
                sink.write(client);
            } catch (IOException e) {
                removeClient(client);
            }
        }
    }

    /** 心跳：向全部 SSE 客户端写注释帧，写失败即摘除死连接。 */
    private void pingAll() {
        for (WebFace.SseClient client : sseOutputs.toArray(WebFace.SseClient[]::new)) {
            try {
                client.send(": ping\n\n");
            } catch (IOException e) {
                removeClient(client);
            }
        }
    }

    /**
     * 摘除死连接；该标签的客户端全部离场时其悬空交互按"无人能答"拒绝（ADR-0008 语义
     * 延伸 + M24 工单 07 按标签化：卡片按标签路由后，"是否仍有人能看见该审批"以标签
     * 为界——其他标签的连接在场不代表本标签会话的卡片可见）。
     * 拒绝经 {@link #scheduleFailClosedCheck(String)} 去抖：立即判空会误杀刷新场景
     * （断旧立新窗口里新连接尚未入列）。包级可见供测试确定性驱动摘除时点——
     * 传入的连接即使不在列表中也生效：判定只看"摘除后该标签是否还有连接"。
     */
    void removeClient(WebFace.SseClient client) {
        sseOutputs.remove(client);
        if (webAnswerer != null && !hasClientOf(client.tabId)) {
            scheduleFailClosedCheck(client.tabId);
        }
    }

    /** 该标签是否仍有 SSE 连接在场。 */
    private boolean hasClientOf(String tabId) {
        for (WebFace.SseClient client : sseOutputs.toArray(WebFace.SseClient[]::new)) {
            if (client.tabId.equals(tabId)) {
                return true;
            }
        }
        return false;
    }

    /** 调度去抖复查：宽限后该标签仍无连接才 fail-closed 其挂起项；窗口内新摘除重置窗口。 */
    private void scheduleFailClosedCheck(String tabId) {
        java.util.concurrent.ScheduledFuture<?> prior = failClosedChecks.put(tabId,
                heartbeat.schedule(() -> failClosedIfNoClient(tabId),
                        WebFace.FAIL_CLOSED_GRACE_MS, TimeUnit.MILLISECONDS));
        if (prior != null) {
            prior.cancel(false);
        }
    }

    /** 宽限期到：该标签仍无客户端在场才判定"无人能答"（只拒该标签的挂起项）。
     * 触发即摘除表项——按 tabId 慢性累积的防泄漏收口（窗口内新摘除已重新占位）。 */
    private void failClosedIfNoClient(String tabId) {
        failClosedChecks.remove(tabId);
        if (webAnswerer != null && !hasClientOf(tabId)) {
            webAnswerer.failClosedFor(tabId);
        }
    }

    /** 新连接入列（handleEvents 握手完成时调用）。 */
    void add(WebFace.SseClient client) {
        sseOutputs.add(client);
    }

    /** 当前活跃的 SSE 连接数（观测用）。 */
    int connectionCount() {
        return sseOutputs.size();
    }

    /** SSE 会话事件流：连接帧 + 回放（尾部快照/增量）+ 实时广播（断开摘除输出流）。
     * 首连（无 Last-Event-ID）发尾部窗口快照（ADR-0013）；断线重连带游标只补其后事件（ADR-0010）。
     * 连接按 tabId 归属（M24 工单 07）——回放与实时推送都只覆盖所属标签的会话。 */
    void streamEvents(HttpExchange exchange, TabContext tab, int pageSize) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        WebFace.SseClient client = new WebFace.SseClient(exchange.getResponseBody(), tab.tabId);
        add(client);
        try {
            // 连接帧是 SSE 注释（冒号行），不是 data 帧——前端 JSON.parse 不消费它
            client.send(": connected\n\n");
            // 回放窗口：replay/start 告知模式与窗口头（前端据此整窗替换或保留存量）→ 事件帧（带
            // 日志序号 id）→ replay/done 边界帧（前端回放结束钩子：EmptyHero 判定与侧栏刷新）
            String cursor = exchange.getRequestHeaders().getFirst(LAST_EVENT_ID_HEADER);
            Session bound = tab.session; // 单次取用：换绑并发下事件快照与窗口映射必须同源
            List<SessionEvent> events = bound.events(); // 共享不可变快照（ADR-0014）：一次取用遍历全程稳定
            ReplayWindow window = resolveReplayWindow(cursor, events, bound, pageSize);
            // 连接观测：回放模式与游标——诊断重连行为（断线重连应见 incremental）
            log.debug("SSE 连接：模式={}，游标={}，事件数={}", window.mode(), cursor, events.size());
            var header = WebHttp.JSON.createObjectNode().put("type", "replay/start").put("mode", window.mode());
            // 响应回带会话 id（M26-06）：前端核对不符即丢弃——复合游标的第二道防线
            header.put(FIELD_SESSION_ID, bound.id());
            if (window.tailSnapshot()) {
                header.put("hasMore", window.hasMore()).put("earlierCount", window.earlierCount());
            }
            client.send(dataFrame(header.toString()));
            for (int i = window.from(); i < events.size(); i++) {
                client.send(dataFrameWithId(bound.id(), i, WebHttp.toJson(events.get(i))));
            }
            client.send(dataFrame("{\"type\":\"replay/done\"}"));
        } catch (Exception e) {
            // 回放中断（含运行时异常）即摘除断连——客户端经 EventSource 重连重新回放
            removeClient(client);
            exchange.close();
        }
    }

    /** 回放窗口：起点下标 + 模式；尾部快照模式头帧额外携带 hasMore 与更早计数。 */
    private record ReplayWindow(int from, String mode, boolean tailSnapshot,
                                boolean hasMore, int earlierCount) { }

    /**
     * 解析重连游标决定回放窗口（M26-06 复合游标）：游标为复合形态「会话id#序号」——
     * 会话 id 与当前绑定相等且序号落在日志范围内 → 只补其后事件（增量，ADR-0010）；
     * 别会话的游标（换绑/服务重启后重连携旧书签）整体作废 → 尾部窗口快照重对齐
     * （ADR-0013），不再静默从错误位置续播；无游标、复合段非法/越界、或裸数字
     * （旧形态，不再作有效续播凭据）→ 同样尾部快照兜底。日志 append-only、治理为
     * 纯读侧（不改编号），序号越界只见于游标陈旧——按重对齐处理。
     */
    private static ReplayWindow resolveReplayWindow(String cursor, List<SessionEvent> events,
                                                    Session bound, int pageSize) {
        if (cursor != null && !cursor.isBlank()) {
            int cursorSep = cursor.indexOf(CURSOR_SEP);
            // 会话段与当前绑定相等才可作增量凭据；分隔符缺失（裸数字旧形态）不受理
            boolean sessionMatched = cursorSep > 0
                    && cursor.substring(0, cursorSep).equals(bound.id());
            if (sessionMatched) {
                try {
                    int parsed = Integer.parseInt(cursor.substring(cursorSep + 1).strip());
                    if (parsed >= 0 && parsed < events.size()) {
                        return new ReplayWindow(parsed + 1, "incremental", false, false, 0);
                    }
                } catch (NumberFormatException ignored) {
                    // 序号段非法按无游标处理（尾部快照兜底）
                }
            }
        }
        Session.TailWindow tail = bound.tailWindow(pageSize);
        return new ReplayWindow(tail.startEvent(), "tail-snapshot", true,
                tail.earlierMessages() > 0, tail.earlierMessages());
    }

    /**
     * 非会话帧（replay/start、replay/done、run/error、心跳注释）：不带序号——这些帧
     * 不落会话日志，无下标可锚；给它们安上别的序号会污染浏览器游标（客户端会误认为该
     * 序号的日志事件已收到，重连时跳过它）。
     */
    private static String dataFrame(String payload) {
        return "data: " + payload.replace("\n", "\ndata: ") + "\n\n";
    }

    /**
     * 会话事件帧：带复合游标 id（「会话id#日志序号」，M26-06）——浏览器重连以
     * Last-Event-ID 原样回传，服务端经 {@link #resolveReplayWindow} 校验会话绑定后
     * 才作增量凭据（防换绑/重启后旧游标错位续播；复合游标与双侧核对见 ADR-0028 拒绝的选项段）。
     */
    private static String dataFrameWithId(String sessionId, int seq, String payload) {
        return "id: " + sessionId + CURSOR_SEP + seq + "\n" + dataFrame(payload);
    }

    /** 停机收口（WebFace.stop 调用）：心跳停止、连接全关、悬空审批全局 fail-closed
     * （停机即无人能答，别让 turn 线程空等 10 分钟兜底）。 */
    void shutdown() {
        heartbeat.shutdownNow();
        for (WebFace.SseClient client : List.copyOf(sseOutputs)) {
            client.close();
        }
        sseOutputs.clear();
        if (webAnswerer != null) {
            webAnswerer.failClosedAll();
        }
        failClosedChecks.clear();
    }
}
