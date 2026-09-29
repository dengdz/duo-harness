package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.sessionquery.SessionHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 会话生命周期端点（M28 工单 06 拆分二刀）：会话的列出/新建/切换/分页/导出、
 * 子会话回放与全文检索——「会话作为数据对象」的七只端点单点；对话与交互端点
 * 在 {@link WebEndpoints}。持门面引用按域取状态（同包协作）。
 */
final class WebSessionEndpoints {

    private static final Logger log = LoggerFactory.getLogger(WebSessionEndpoints.class);

    /** 会话 id 白名单（Session.newId 的生成形态：日期时间 + 4 位十六进制后缀）。 */
    private static final java.util.regex.Pattern SESSION_ID =
            java.util.regex.Pattern.compile("\\d{8}-\\d{6}-[0-9a-f]{4}");

    /** 侧栏搜索单次返回的命中上限（呈现位侧常量；工具侧上限由插件配置管）。 */
    static final int SEARCH_LIMIT = 20;

    private final WebFace face;

    WebSessionEndpoints(WebFace face) {
        this.face = face;
    }

    /** 开新会话（绑定发起标签，M24 工单 07）：换绑该标签事件流 + 回调重建其 agent；
     * 供给者未装配/创建失败 → 500，不断连接。该标签 turn 执行中 409——换绑会关闭
     * 正在写入的会话（换绑与重建 agent 是同一动作两面，BUG-20260914-01 教训）。 */
    void handleSessionNew(HttpExchange exchange) throws IOException {
        byte[] discarded = WebHttp.readBodyLimited(exchange); // 请求体必须清空（keep-alive 连接复用正确性）
        if (discarded == null) {
            WebHttp.respondEmpty(exchange, 413);
            return;
        }
        if (!WebHttp.requirePost(exchange)) {
            return;
        }
        TabContext tab = face.tabs.resolveTab(exchange);
        if (tab == null) {
            return;
        }
        // CAS 占有 busy 至换绑收口（finally 释放）：检查-行动两段式会留出"检查通过后、
        // 换绑落地前"新 turn 潜入的窗口——被换绑关闭的正是它正在写的会话（BUG-20260914-01）
        if (!tab.busy.compareAndSet(false, true)) {
            WebHttp.respondText(exchange, 409, "当前有对话在执行中，完成后再开新话题");
            return;
        }
        try {
            face.tabs.newSessionFor(tab);
        } catch (Exception e) {
            // 异常细节仅服务端日志留痕——错误响应不回显内部消息（M10-02 脱敏）
            log.warn("新会话创建失败", e);
            WebHttp.respondText(exchange, 500, "新会话创建失败");
            return;
        } finally {
            tab.busy.set(false);
        }
        WebHttp.respondJson(exchange, 200, "{\"id\":\"" + tab.session.id() + "\"}");
    }

    /** 会话列表（侧栏）：修改时间倒序。 */
    void handleSessions(HttpExchange exchange) throws IOException {
        String json = sessionsJson(exchange);
        if (json != null) {
            WebHttp.respondJson(exchange, 200, json);
        }
    }

    /**
     * 会话检索（M21 工单 08）：{@code GET /api/search?q=关键词} → 命中列表
     * （会话 + 最强匹配事件 + snippet，【】为命中标记）。session-query 行未装
     * 时 503（前端提示"未装配"而不是静默空结果）。
     */
    void handleSearch(HttpExchange exchange) throws IOException {
        if (face.sessionQuery == null) {
            WebHttp.respondText(exchange, 503, "会话检索未装配（yml 未装 session-query 插件行）");
            return;
        }
        // queryParam 不做 URL 解码——中文检索词必须显式 decode（浏览器 fetch 百分号编码）
        String q;
        try {
            q = WebHttp.urlDecode(WebHttp.queryParam(exchange, "q"));
        } catch (IllegalArgumentException e) {
            WebHttp.respondText(exchange, 400, "检索词编码非法");
            return;
        }
        if (q == null || q.strip().isEmpty()) {
            WebHttp.respondText(exchange, 400, "缺少检索词 q");
            return;
        }
        List<SessionHit> hits;
        try {
            hits = face.sessionQuery.search(q.strip(), SEARCH_LIMIT);
        } catch (RuntimeException e) {
            // 检索是读放大路径，不炸穿面（栈进日志、面收 500 而非空响应）
            log.error("会话检索失败 q={}", q, e);
            WebHttp.respondText(exchange, 500, "会话检索失败（见服务端日志）");
            return;
        }
        var root = WebHttp.JSON.createObjectNode();
        root.put("query", q.strip());
        var arr = root.putArray("hits");
        for (SessionHit hit : hits) {
            arr.addObject()
                    .put("sessionId", hit.sessionId())
                    .put("title", hit.title())
                    .put("lastModifiedMs", hit.lastModifiedMs())
                    .put("eventIndex", hit.eventIndex())
                    .put("eventType", hit.eventType())
                    .put("snippet", hit.snippet());
        }
        WebHttp.respondJson(exchange, 200, root.toString());
    }

    /**
     * 会话导出下载流（M21 工单 09，ADR-0022 决策 9；M26-07 收口采纳显式寻址——用户
     * 验收实测提案，DSH 同款）：{@code GET /api/session/export?format=…&sessionId=<id>}
     * → 附件下载（Content-Disposition 命名 duo-session-&lt;id&gt;.md/.jsonl）。
     * sessionId 必带——导出不再依赖标签绑定状态，匿名/跨标签导出错会话的整类缺陷
     * 就此消除，且解锁"导出未打开的会话"（临时加载、导出后释放；被他进程占用 409）。
     * 非法格式/缺参/非法 id 400，未知会话 404。
     */
    void handleSessionExport(HttpExchange exchange) throws IOException {
        try {
            String formatArg = WebHttp.queryParam(exchange, "format");
            dev.duo.harness.session.SessionExport.Format parse =
                    dev.duo.harness.session.SessionExport.Format.parse(formatArg);
            if (parse == null) {
                WebHttp.respondText(exchange, 400, "未知格式: \"" + formatArg + "\"（可选 markdown | json）");
                return;
            }
            String sessionId = WebHttp.queryParam(exchange, "sessionId");
            if (sessionId.isEmpty() || !SESSION_ID.matcher(sessionId).matches()) {
                WebHttp.respondText(exchange, 400, "缺少或非法的 sessionId 参数（显式寻址——M26-07 收口采纳）");
                return;
            }
            Path jsonl = face.sessionsDir.resolve(sessionId + ".jsonl");
            if (!Files.isRegularFile(jsonl)) {
                WebHttp.respondText(exchange, 404, "会话不存在: " + sessionId);
                return;
            }
            // 活跃会话（本进程持锁）用内存实例；未打开的临时加载、导出后释放——
            // 被他进程占用 409 点名（不静默改导别的会话）
            Session target = dev.duo.harness.session.Session.heldSession(jsonl);
            boolean borrowed = false;
            if (target == null) {
                try {
                    target = dev.duo.harness.session.Session.load(jsonl);
                    borrowed = true;
                } catch (dev.duo.harness.session.SessionLockedException e) {
                    WebHttp.respondText(exchange, 409, "会话被其他进程占用，无法导出: " + sessionId);
                    return;
                }
            }
            String fileName = dev.duo.harness.session.SessionExport.fileName(target.id(), parse);
            // 对账在发头之前（头已出便无法改状态码——report 异常仍可 500 送达）
            var report = parse == dev.duo.harness.session.SessionExport.Format.MARKDOWN
                    ? dev.duo.harness.agent.deliverable.ChangeSummary.report(target) : null;
            exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + fileName + "\"");
            exchange.getResponseHeaders().set("Content-Type",
                    parse == dev.duo.harness.session.SessionExport.Format.MARKDOWN
                            ? "text/markdown; charset=utf-8"
                            : "application/x-ndjson");
            // 流式下载（M26-05）：chunked 写出，大会话不整包驻内存；中途失败只能
            // 截断连接（状态头已出）——日志点名，浏览器侧表现为下载未完成
            exchange.sendResponseHeaders(200, 0);
            try (var writer = new java.io.OutputStreamWriter(exchange.getResponseBody(),
                    StandardCharsets.UTF_8)) {
                if (parse == dev.duo.harness.session.SessionExport.Format.MARKDOWN) {
                    dev.duo.harness.session.SessionExport.renderMarkdown(target, report, writer);
                } else {
                    dev.duo.harness.session.SessionExport.renderJsonl(target, writer);
                }
                writer.flush();
            } catch (IOException e) {
                log.error("/api/session/export 流式写出中断: {}", fileName, e);
            } finally {
                if (borrowed) {
                    target.close(); // 临时加载的会话导出即释放（活跃实例归标签持有，不动）
                }
            }
        } catch (Throwable t) {
            log.error("/api/session/export 处理失败", t);
            WebHttp.respondText(exchange, 500, "导出失败（详情见服务端日志）");
        }
    }

    /**
     * 切换会话（绑定发起标签，M24 工单 07）：{id} → 加载该会话并换绑**发起标签**
     * （其他标签的绑定与事件流不动），SSE 推送新会话存量回放；会话变更回调重建
     * 该标签 agent——不重建即分脑（agent 写旧会话、页面看新会话）。
     * id 按生成形态白名单校验：路径分隔符/穿越串一律 400，不进路径解析。
     * 该标签 turn 执行中 409（同 /new 守卫）；目标会话被其他标签/进程占用由
     * 独占锁拦（SessionLockedException → 409 点名冲突）。
     */
    void handleSessionSwitch(HttpExchange exchange) throws IOException {
        if (!WebHttp.requirePost(exchange)) {
            return;
        }
        byte[] raw = WebHttp.readBodyLimited(exchange);
        if (raw == null) {
            WebHttp.respondEmpty(exchange, 413);
            return;
        }
        TabContext tab = face.tabs.resolveTab(exchange);
        if (tab == null) {
            return;
        }
        try {
            String id = WebHttp.JSON.readTree(new String(raw, StandardCharsets.UTF_8)).path("id").asText("");
            if (id.isBlank() || !SESSION_ID.matcher(id).matches()) {
                WebHttp.respondEmpty(exchange, 400);
                return;
            }
            if (id.equals(tab.session.id())) {
                // 切到当前会话：幂等成功——重新 load 自己必撞独占锁（OverlappingFileLockException），
                // 而语义上本就无需动作（侧栏点当前项、重复提交切换请求都不该失败）
                WebHttp.respondJson(exchange, 200, "{\"switched\":true}");
                return;
            }
            // CAS 占有 busy 至换绑收口（finally 释放）：同 /new 的 TOCTOU 堵法——
            // Session.load 文件 IO 期间新 turn 潜入即写即将被 close 的会话
            if (!tab.busy.compareAndSet(false, true)) {
                WebHttp.respondText(exchange, 409, "当前有对话在执行中，完成后再切换会话");
                return;
            }
            try {
                Session loaded = Session.load(face.sessionsDir.resolve(id + ".jsonl"));
                face.tabs.rebind(tab, loaded);
            } finally {
                tab.busy.set(false);
            }
            WebHttp.respondJson(exchange, 200, "{\"switched\":true}");
        } catch (dev.duo.harness.session.SessionLockedException e) {
            // 会话被占（本进程另一入口或其他进程在用）：明确点名冲突，不混入通用失败文案。
            // 业务拒绝只留消息不打堆栈（验收实测反馈：双开保护每次拒绝刷全栈，形似事故）
            log.info("会话切换被拒（占用冲突）: {}", e.getMessage());
            WebHttp.respondText(exchange, 409, e.brief());
        } catch (Exception e) {
            // 异常细节（含文件系统路径）仅服务端日志留痕，不回显给响应体（M10-02 脱敏）
            log.warn("会话切换失败", e);
            WebHttp.respondText(exchange, 404, "切换失败：会话不存在或不可读");
        }
    }

    /**
     * 历史分页（ADR-0013）：before（事件序号）之前的尾页事件——响应携 sessionId（会话
     * 绑定回带，M26-06 前端第二道核对）、startEvent（窗口首事件下标，前端更新加载锚点）、
     * events、hasMore、earlierCount；服务端每次全量投影定消息边界。请求必须携 sid
     * （当前会话 id）：缺失 400（旧形态一步切拒绝）、不符 409（换绑后在途翻页作废，
     * 前端静默丢弃重对齐）。
     */
    void handleSessionPage(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            WebHttp.respondEmpty(exchange, 405);
            return;
        }
        TabContext tab = face.tabs.resolveTab(exchange);
        if (tab == null) {
            return;
        }
        String sid = WebHttp.queryParam(exchange, WebFace.PARAM_SID);
        if (sid.isEmpty()) {
            // 复合游标协议（M26-06）：分页请求必须绑定会话——旧形态裸 before 不再受理（同包发布一步切）
            WebHttp.respondEmpty(exchange, 400);
            return;
        }
        Session bound = tab.session;
        if (!sid.equals(bound.id())) {
            // 游标属于别的会话（换绑后在途翻页）：明确失效而非装错数据——前端收 409 丢弃整页重对齐
            WebHttp.respondEmpty(exchange, 409);
            return;
        }
        int before;
        try {
            before = Integer.parseInt(WebHttp.queryParam(exchange, "before"));
        } catch (NumberFormatException e) {
            WebHttp.respondEmpty(exchange, 400);
            return;
        }
        List<SessionEvent> events = bound.events();
        if (before < 0 || before > events.size()) {
            WebHttp.respondEmpty(exchange, 400);
            return;
        }
        Session.TailWindow window = bound.windowBefore(before, face.pageSize); // 首屏/每页同值（ADR-0013）
        var root = WebHttp.JSON.createObjectNode()
                .put(WebFace.FIELD_SESSION_ID, bound.id()) // 响应回带（M26-06）：前端第二道核对——不符即整页丢弃
                .put("startEvent", window.startEvent())
                .put("hasMore", window.earlierMessages() > 0)
                .put("earlierCount", window.earlierMessages());
        var arr = root.putArray("events");
        for (int i = window.startEvent(); i < before; i++) {
            arr.add(WebHttp.JSON.valueToTree(events.get(i)));
        }
        WebHttp.respondJson(exchange, 200, root.toString());
    }

    /**
     * 子任务回放（M15 工单 05，ADR-0015 决策 3）：子会话事件只读回放——静态逐行读
     * **不持锁**（活跃子会话读到部分文件即所见，不与子代理写者争锁）；id 白名单
     * 防路径穿越（与侧栏切换同一 SESSION_ID 形态）；坏行跳过（回放是锦上添花，
     * 不因单行损坏失败）。
     */
    void handleSubagentEvents(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            WebHttp.respondEmpty(exchange, 405);
            return;
        }
        String id = WebHttp.queryParam(exchange, "id");
        if (id == null || !SESSION_ID.matcher(id).matches()) {
            WebHttp.respondEmpty(exchange, 400);
            return;
        }
        Path jsonl = face.sessionsDir.resolve(dev.duo.harness.agent.subagent.SubagentManager.SUBDIRECTORY)
                .resolve(id + ".jsonl");
        var root = WebHttp.JSON.createObjectNode();
        var arr = root.putArray("events");
        root.put("found", Files.isRegularFile(jsonl));
        if (Files.isRegularFile(jsonl)) {
            // 逐行流式读（长会话不做全量驻留）；坏行跳过（探测语义宽松）
            try (var reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (dev.duo.harness.session.SessionFormat.isHeaderLine(line)) {
                        continue; // 版本头不是事件（M26-01）：回放只给事件，新子会话首帧不带头
                    }
                    try {
                        arr.add(WebHttp.JSON.readTree(line));
                    } catch (Exception ignored) {
                        // 单行损坏跳过
                    }
                }
            } catch (IOException e) {
                WebHttp.respondEmpty(exchange, 500);
                return;
            }
        }
        WebHttp.respondJson(exchange, 200, root.toString());
    }

    /** 侧栏 JSON：会话列表（修改时间倒序，current 标记请求标签的当前会话，occupied 占用探测、
     * title 标题——工单 M13-05/06；current 按标签解析，M24 工单 07）。 */
    private String sessionsJson(HttpExchange exchange) throws IOException {
        TabContext tab = face.tabs.resolveTab(exchange);
        if (tab == null) {
            return null;
        }
        var root = WebHttp.JSON.createObjectNode();
        var arr = root.putArray("sessions");
        String currentId = tab.session.id();
        for (Session.SessionSummary summary : Session.list(face.sessionsDir)) {
            Path jsonl = face.sessionsDir.resolve(summary.id() + ".jsonl");
            var node = arr.addObject()
                    .put("id", summary.id())
                    .put("lastModifiedMs", summary.lastModifiedMs())
                    .put("occupied", Session.isOccupied(jsonl))
                    .put("title", Session.titleOf(jsonl));
            node.put("current", summary.id().equals(currentId));
        }
        return root.toString();
    }
}
