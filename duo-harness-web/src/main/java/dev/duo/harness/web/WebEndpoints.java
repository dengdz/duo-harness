package dev.duo.harness.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.commands.CommandEnv;
import dev.duo.harness.agent.commands.CommandOutcome;
import dev.duo.harness.agent.commands.CommandScope;
import dev.duo.harness.agent.commands.CommandsRegistry;
import dev.duo.harness.attachment.AdmittedImage;
import dev.duo.harness.attachment.AttachmentException;
import dev.duo.harness.session.AttachmentRef;
import dev.duo.harness.session.Session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 对话与交互端点域（M28 工单 06 从 WebFace 拆出）：静态单页与资源、状态面、对话
 * 入口与停止、HITL 回答、附件读写、补全、SSE 入口与斜杠命令分支——路由表声明与
 * 各 handleXxx 行为；会话生命周期七端点在 {@link WebSessionEndpoints}。响应写入
 * 统一走 {@link WebHttp} respond 系列。持门面引用按域取状态（同包协作）。
 */
final class WebEndpoints {

    /** @ 补全单次返回候选上限（下拉一屏可读的量）。 */
    static final int FILE_COMPLETE_LIMIT = 20;

    /** 静态资源后缀 → Content-Type（白名单外不服务）。 */
    private static final java.util.Map<String, String> STATIC_TYPES = java.util.Map.of(
            "html", "text/html",
            "css", "text/css",
            "js", "application/javascript");

    private final WebFace face;
    private final WebSessionEndpoints session;

    WebEndpoints(WebFace face) {
        this.face = face;
        this.session = new WebSessionEndpoints(face);
    }

    /** 挂载全部端点（start 收口调用）。 */
    void register(HttpServer server) {
        route(server, "/", this::handleIndexPage);
        route(server, "/web/", this::handleStatic);
        route(server, "/api/status", this::handleStatus);
        route(server, "/api/message", this::handleMessage);
        route(server, "/api/stop", this::handleStop);
        route(server, "/api/attachment/upload", this::handleAttachmentUpload);
        route(server, "/api/attachment/read", this::handleAttachmentRead);
        route(server, "/api/session/new", session::handleSessionNew);
        route(server, "/api/sessions", session::handleSessions);
        route(server, "/api/session/switch", session::handleSessionSwitch);
        route(server, "/api/search", session::handleSearch);
        route(server, "/api/file-complete", this::handleFileComplete);
        route(server, "/api/session/export", session::handleSessionExport);
        route(server, "/api/answer", this::handleAnswer);
        route(server, "/api/session/page", session::handleSessionPage);
        route(server, "/api/subagent/events", session::handleSubagentEvents);
        route(server, "/api/events", this::handleEvents);
    }

    /** 挂载单个端点：统一前置入口栅栏（Host/Origin 校验），通过才交端点处理器。 */
    private void route(HttpServer server, String path, Endpoint endpoint) {
        server.createContext(path, exchange -> {
            if (face.gate.admits(exchange)) {
                endpoint.handle(exchange);
            }
        });
    }

    /** 端点处理器：与 HttpHandler 同形。 */
    @FunctionalInterface
    private interface Endpoint {

        void handle(HttpExchange exchange) throws IOException;
    }

    /**
     * 静态单页（/）：鉴权开启时为子资源 URL 注入 token——link/script 标签不继承
     * 父页查询参数，不注入则首载自断（三轴审查 Spec 轴阻断项）；注入只发生在已过
     * 闸的响应上，token 不落模板文件。
     */
    private void handleIndexPage(HttpExchange exchange) throws IOException {
        String html = new String(readClasspage(), StandardCharsets.UTF_8);
        String token = face.gate.authToken();
        if (token != null) {
            // 组引用 $1 保持正则语义；token 部分经 quoteReplacement 防特殊字符（hex 实际无 $/\）
            html = html.replaceAll("(/web/[A-Za-z0-9._-]+)",
                    "$1?token=" + java.util.regex.Matcher.quoteReplacement(token));
        }
        WebHttp.respondNoCache(exchange, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 静态资源（样式/脚本/vendor 库同路）：/web/ 前缀 + 单段已知后缀文件名白名单——
     * 多段路径、.. 与未知后缀一律 404，资源缺失也 404（不落回单页，坏引用不伪装成功）。
     */
    private void handleStatic(HttpExchange exchange) throws IOException {
        String name = exchange.getRequestURI().getPath().substring("/web/".length());
        String type = name.isEmpty() || name.contains("/") || name.contains("..")
                ? null : STATIC_TYPES.get(suffixOf(name));
        byte[] body = type == null ? null : WebHttp.readClassResource("/web/" + name);
        if (body == null) {
            WebHttp.respondEmpty(exchange, 404);
            return;
        }
        WebHttp.respondNoCache(exchange, 200, type + "; charset=utf-8", body);
    }

    /** 状态面 JSON。 */
    private void handleStatus(HttpExchange exchange) throws IOException {
        String json = statusJson(exchange);
        if (json != null) {
            WebHttp.respondJson(exchange, 200, json);
        }
    }

    /**
     * 停止入口（M23 工单 02，ADR-0025 决策一）：POST /api/stop 请求协作式中断——
     * 与 CLI 的 /stop、Ctrl+C 单击同语义：当前工具终止、已流出文本保留并打中断
     * 标记、未派发调用补合成结果；会话停在可恢复态，下一条消息即续接。
     * 空闲（无 send 在飞）返回 409——按钮侧据此复位。
     */
    private void handleStop(HttpExchange exchange) throws IOException {
        if (!WebHttp.requirePost(exchange)) {
            return;
        }
        TabContext tab = face.tabs.resolveTab(exchange);
        if (tab == null) {
            return;
        }
        ChatAgent current = tab.agent;
        if (current == null) {
            WebHttp.respondText(exchange, 503, "对话面未就绪（agent 未装配）");
            return;
        }
        if (!tab.agentRunning.get() || !current.requestInterrupt()) {
            WebHttp.respondText(exchange, 409, "当前无执行中任务");
            return;
        }
        WebHttp.respondJson(exchange, 202, "{\"outcome\":\"interrupt-requested\"}");
    }

    /**
     * HITL 回答端点（M16 工单 07 结构化协议；M24 工单 02 升级按卡片 id 回填）：
     * 携 {@code id} 时 {@code {"id":"...","decision":"approve"|"reject"|"always-project"|
     * "always-session"|"answer","answers":["..."]}}（answer 形态须非空 answers）——
     * 精确完成对应卡片（销 M23 按位置回填坑）；无 id 的旧形态 {@code {"decision":...}} /
     * {@code {"answers":...}} 兼容完成最旧一项（缓存页兜底）。缺失或取值非法一律 400。
     * 不做字符串嗅探：自由文本答案里的"拒绝"二字是普通回答，不改变判定语义。
     */
    private void handleAnswer(HttpExchange exchange) throws IOException {
        if (!WebHttp.requirePost(exchange)) {
            return;
        }
        if (face.webAnswerer == null) {
            WebHttp.respondEmpty(exchange, 503);
            return;
        }
        byte[] raw = WebHttp.readBodyLimited(exchange);
        if (raw == null) {
            WebHttp.respondEmpty(exchange, 413);
            return;
        }
        boolean completed;
        try {
            JsonNode node = WebHttp.JSON.readTree(new String(raw, StandardCharsets.UTF_8));
            if (node.hasNonNull("decision") && node.hasNonNull("answers")) {
                WebHttp.respondEmpty(exchange, 400); // 两形态互斥：同时出现按协议错误拒绝
                return;
            }
            List<String> values = new java.util.ArrayList<>();
            if (node.hasNonNull("answers") && node.get("answers").isArray()) {
                node.get("answers").forEach(n -> values.add(n.asText()));
            }
            String decision = node.hasNonNull("decision") ? node.get("decision").asText("") : "answer";
            if (node.hasNonNull("id") && !node.get("id").asText("").isBlank()) {
                // 按卡片 id 精确回填（M24 工单 02）；未知决策词协议错误（fail-closed 拒绝语义不吞坏值）
                if (!"approve".equals(decision) && !"reject".equals(decision)
                        && !"always-project".equals(decision) && !"always-session".equals(decision)
                        && !"answer".equals(decision)) {
                    WebHttp.respondEmpty(exchange, 400);
                    return;
                }
                if ("answer".equals(decision) && values.isEmpty()) {
                    WebHttp.respondEmpty(exchange, 400);
                    return;
                }
                completed = face.webAnswerer.completeById(node.get("id").asText(), decision, values);
            } else if (node.hasNonNull("decision")) {
                if (!"approve".equals(decision) && !"reject".equals(decision)) {
                    WebHttp.respondEmpty(exchange, 400);
                    return;
                }
                completed = face.webAnswerer.complete("approve".equals(decision), List.of());
            } else if (!values.isEmpty()) {
                completed = face.webAnswerer.complete(true, values);
            } else {
                WebHttp.respondEmpty(exchange, 400);
                return;
            }
        } catch (Exception e) {
            WebHttp.respondEmpty(exchange, 400);
            return;
        }
        WebFace.log.debug("/api/answer completed={}", completed);
        WebHttp.respondJson(exchange, 200, "{\"completed\":" + completed + "}");
    }

    /** 视觉闸门现读（llm.vision；未启用 = 附件路径全拒）。 */
    private boolean visionEnabled() {
        return face.visionGate != null && face.visionGate.getAsBoolean();
    }

    /** 附件上传（M21 工单 04）：vision 闸门 → base64 解码 → 准入入库 → 返回元数据。 */
    private void handleAttachmentUpload(HttpExchange exchange) throws IOException {
        if (!WebHttp.requirePost(exchange)) {
            return;
        }
        if (face.attachments == null) {
            WebHttp.respondText(exchange, 503, "附件服务未装配");
            return;
        }
        if (!visionEnabled()) {
            WebHttp.respondText(exchange, 409, "当前模型不支持图片（llm.vision 未启用）");
            return;
        }
        byte[] raw = WebHttp.readBodyLimited(exchange, face.attachments.maxImageBytes() * 2L);
        if (raw == null) {
            WebHttp.respondText(exchange, 413, "图片过大");
            return;
        }
        JsonNode node;
        try {
            node = WebHttp.JSON.readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            WebHttp.respondEmpty(exchange, 400);
            return;
        }
        String data = node.path("data").asText("");
        String name = node.path("name").asText("");
        if (data.isBlank()) {
            WebHttp.respondEmpty(exchange, 400);
            return;
        }
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(data.strip());
        } catch (IllegalArgumentException e) {
            WebHttp.respondText(exchange, 400, "base64 非法");
            return;
        }
        AdmittedImage admitted;
        try {
            admitted = face.attachments.storeImage(bytes, null);
        } catch (AttachmentException e) {
            WebHttp.respondText(exchange, 422, e.getMessage());
            return;
        }
        var root = WebHttp.JSON.createObjectNode()
                .put("attachmentId", admitted.attachmentId())
                .put("mediaType", admitted.mediaType())
                .put("bytes", admitted.bytes())
                .put("width", admitted.width())
                .put("height", admitted.height())
                .put("name", name);
        WebHttp.respondJson(exchange, 200, root.toString());
    }

    /** 附件授权读取（M21 工单 04）：先验证请求标签会话日志确实引用了此 id，再回字节。 */
    private void handleAttachmentRead(HttpExchange exchange) throws IOException {
        if (face.attachments == null) {
            WebHttp.respondEmpty(exchange, 503);
            return;
        }
        TabContext tab = face.tabs.resolveTab(exchange);
        if (tab == null) {
            return;
        }
        String id = WebHttp.queryParam(exchange, "id");
        if (id == null || !id.matches("[0-9a-f]{64}")) {
            WebHttp.respondEmpty(exchange, 400);
            return;
        }
        Session bound = tab.session;
        AttachmentRef ref = bound.referencedAttachments().stream()
                .filter(r -> r.attachmentId().equals(id))
                .findFirst().orElse(null);
        if (ref == null || !face.attachments.exists(id)) {
            WebHttp.respondEmpty(exchange, 404);
            return;
        }
        byte[] bytes = Files.readAllBytes(face.attachments.objectPath(id));
        exchange.getResponseHeaders().set("Content-Type", ref.mediaType());
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void handleMessage(HttpExchange exchange) throws IOException {
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
        String text;
        java.util.List<AttachmentRef> attachmentRefs = new java.util.ArrayList<>();
        try {
            JsonNode node = WebHttp.JSON.readTree(new String(raw, StandardCharsets.UTF_8));
            text = node.path("text").asText("");
            JsonNode atts = node.path("attachments");
            if (atts.isArray()) {
                for (JsonNode n : atts) {
                    attachmentRefs.add(new AttachmentRef(n.path("attachmentId").asText(""),
                            n.path("mediaType").asText(""), n.path("bytes").asLong(0),
                            n.path("name").asText("")));
                }
            }
        } catch (Exception e) {
            WebFace.log.debug("消息附件引用解析失败（按无附件处理）: {}", e.toString());
            WebHttp.respondEmpty(exchange, 400);
            return;
        }
        if (!attachmentRefs.isEmpty()) {
            if (face.attachments == null) {
                WebHttp.respondText(exchange, 503, "附件服务未装配");
                return;
            }
            if (!visionEnabled()) {
                WebHttp.respondText(exchange, 409, "当前模型不支持图片（llm.vision 未启用）");
                return;
            }
            if (attachmentRefs.size() > face.attachments.maxImagesPerMessage()) {
                WebHttp.respondText(exchange, 413, "单消息图片数超上限（"
                        + attachmentRefs.size() + " > " + face.attachments.maxImagesPerMessage() + "）");
                return;
            }
            for (AttachmentRef ref : attachmentRefs) {
                if (ref.attachmentId().isBlank() || !face.attachments.exists(ref.attachmentId())) {
                    WebHttp.respondText(exchange, 400, "附件未上传或不存在: " + ref.attachmentId());
                    return;
                }
            }
            long totalBytes = attachmentRefs.stream().mapToLong(AttachmentRef::bytes).sum();
            if (totalBytes > face.attachments.maxMessageImageBytes()) {
                WebHttp.respondText(exchange, 413, "单消息图片总字节超上限（"
                        + totalBytes + " > " + face.attachments.maxMessageImageBytes() + "）");
                return;
            }
        }
        if (text.isBlank() && attachmentRefs.isEmpty()) {
            WebHttp.respondEmpty(exchange, 400);
            return;
        }
        // 斜杠前置命令解释（M19，ADR-0020 决策 3/5）：命令注册表 → 技能直调 → 未知报错，
        // 与 CLI 共享同一入口顺序——斜杠文本从此不再透传进模型历史（M12-03 事故销账）。
        // 技能直调（prompt outcome）落回下方普通提交路径，注入文本照旧进模型历史
        if (!attachmentRefs.isEmpty() && text.strip().startsWith("/")) {
            WebHttp.respondText(exchange, 400, "斜杠命令不支持附件");
            return;
        }
        final String userText;
        String stripped = text.strip();
        if (stripped.startsWith("/")) {
            String skillInjected = handleCommand(exchange, stripped, tab);
            if (skillInjected == null) {
                return; // 命令分支已响应（命中执行或拒绝）
            }
            userText = skillInjected; // 技能直调：指令前缀注入文本照旧走 agent
        } else {
            userText = text;
        }
        ChatAgent current = tab.agent;
        if (current == null) {
            WebHttp.respondText(exchange, 503, "对话面未就绪（agent 未装配）");
            return;
        }
        if (!tab.busy.compareAndSet(false, true)) {
            // 运行中治理（M19 steer，ADR-0020 决策 8）：agent 执行中的消息进注入收件箱
            // （迭代边界排干为普通 user/message，下一轮请求可见）；agent 未执行（busy 被
            // 非 busySafe 命令互斥持有）时不入收件箱——保留 409（消息不会被"当前步骤"消化）
            if (tab.agentRunning.get() && current.injectUserMessage(userText)) {
                attachmentRefs.forEach(tab.session::appendUserAttachment); // 引用先于注入的 user/message
                WebHttp.respondJson(exchange, 202,
                        "{\"outcome\":\"injected\",\"text\":\"已注入，待当前步骤完成\"}");
            } else {
                WebHttp.respondText(exchange, 409, "已有对话在执行中（单入口串行）");
            }
            return;
        }
        attachmentRefs.forEach(tab.session::appendUserAttachment); // 引用先于 agent 侧 user/message
        exchange.sendResponseHeaders(202, -1);
        face.startAgentTurn(userText, tab);
    }

    /**
     * 斜杠命令分支（M19）：经命令注册表共享入口解释输入——命中命令同步执行于 Web
     * 进程内（不占 agent 单飞窗口、不 append user/message），run/done 审计事件经
     * 会话监听器走既有 SSE 推送（前端渲染轻量命令行，刷新/回放可见）；拒绝三类
     * （未知/适用面/busy）无审计事件，文本经响应体交前端 toast。命中返回 null；
     * 技能直调返回注入文本（调用方落回普通 agent 提交路径）。命令的 forward 转发文本
     * 在 Web 面不消费（当前唯一转发方 /plan 为 CLI 专属）——转发型命令上 Web 前须先
     * 补呈现位消费路径。
     */
    private String handleCommand(HttpExchange exchange, String line, TabContext tab) throws IOException {
        CommandsRegistry commands;
        try {
            // 惰性寻址（InteractivePolicy 同款）：命令服务由装配保证在场（web 插件 inject），
            // 测试骨架等缺席场景不误透传——斜杠透传正是 M12-03 事故
            commands = face.ctx.as(WebServiceViews.Commands.class).commands();
        } catch (Exception e) {
            WebHttp.respondText(exchange, 503, "命令服务未挂载（装配缺 commands 插件行）");
            return null;
        }
        // 非 busySafe 命令（如 /compact 动上下文）执行期占住单飞标志：agent send 与命令
        // 互斥——压缩摘要走 LLM 的窗口内不会再启动 agent 轮次（投影结构不被交错改写）。
        // agent 执行中不抢互斥——交 dispatch 的 busySafe 分级回应（"执行中，需等待空闲"）。
        // 互斥与探针都是标签粒度（M24 工单 07）：A 标签跑 agent 不拦 B 标签的 /compact
        String commandName = line.split("\\s+", 2)[0].substring(1);
        dev.duo.harness.agent.commands.CommandDefinition matched = commands.find(commandName);
        boolean needsMutex = matched != null && !matched.busySafe();
        boolean mutexHeld = false;
        if (needsMutex && !tab.agentRunning.get()) {
            if (!tab.busy.compareAndSet(false, true)) {
                WebHttp.respondJson(exchange, 202, "{\"outcome\":\"command\",\"text\":"
                        + WebHttp.JSON.writeValueAsString("已有命令在执行中，请稍候再试。") + "}");
                return null;
            }
            mutexHeld = true;
        }
        try {
            CommandOutcome outcome = commands.dispatch(line,
                    new CommandEnv(CommandScope.WEB, () -> tab.session, s -> { }, () -> { },
                            tab.agentRunning::get),
                    skillsOrNull());
            if (!outcome.isCommand()) {
                return outcome.text(); // 技能直调注入文本
            }
            if (outcome.audited()) {
                WebHttp.respondJson(exchange, 202, "{\"outcome\":\"command\"}");
            } else {
                WebHttp.respondJson(exchange, 202, "{\"outcome\":\"command\",\"text\":"
                        + WebHttp.JSON.writeValueAsString(outcome.text()) + "}");
            }
            return null;
        } finally {
            if (mutexHeld) {
                tab.busy.set(false);
            }
        }
    }

    /** 技能注册表惰性寻址（技能直调入口第二级；缺席即无技能，null 安全）。 */
    private dev.duo.harness.agent.skills.SkillRegistry skillsOrNull() {
        try {
            return face.ctx.as(WebServiceViews.Skills.class).skills();
        } catch (Exception e) {
            return null;
        }
    }

    /** SSE 会话事件流：连接帧 + 回放 + 实时广播（机制在 {@link WebSseHub}）。 */
    private void handleEvents(HttpExchange exchange) throws IOException {
        TabContext tab = face.tabs.resolveTab(exchange);
        if (tab == null) {
            return;
        }
        face.hub.streamEvents(exchange, tab, face.pageSize);
    }

    private void handleFileComplete(HttpExchange exchange) throws IOException {
        try {
            dev.duo.harness.agent.fileref.FileReferenceService refs = face.fileRefs;
            if (refs == null) {
                WebHttp.respondText(exchange, 503, "补全服务未装配（无 workspace）");
                return;
            }
            // queryParam 不做 URL 解码——路径 token 显式 decode
            String q;
            try {
                q = java.net.URLDecoder.decode(WebHttp.queryParam(exchange, "q"),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                WebHttp.respondText(exchange, 400, "token 编码非法");
                return;
            }
            var root = WebHttp.JSON.createObjectNode();
            var arr = root.putArray("suggestions");
            for (var c : refs.complete(q, FILE_COMPLETE_LIMIT)) {
                arr.addObject().put("path", c.path()).put("directory", c.directory());
            }
            WebHttp.respondJson(exchange, 200, root.toString());
        } catch (Throwable t) {
            WebFace.log.error("/api/file-complete 处理失败", t);
            WebHttp.respondText(exchange, 500, "补全处理失败: " + t);
        }
    }

    /** 状态面 JSON：插件快照 + 工具清单 + 上下文占用（与治理计量同源，无治理时省略；
     * 占用按请求标签的会话解析——M24 工单 07）。 */
    private String statusJson(HttpExchange exchange) throws IOException {
        TabContext tab = face.tabs.resolveTab(exchange);
        if (tab == null) {
            return null;
        }
        try {
            var root = WebHttp.JSON.createObjectNode();
            var plugins = root.putArray("plugins");
            for (var snapshot : face.ctx.snapshots()) {
                plugins.addObject().put("name", snapshot.name()).put("state", snapshot.state().name());
            }
            var toolsNode = root.putArray("tools");
            for (var definition : face.tools.list()) {
                toolsNode.addObject().put("name", definition.name()).put("description", definition.description());
            }
            // 连接器状态（M24 工单 05）：MCP 等外部连接器的生命周期标注（GAVE_UP = 不可用）
            if (face.ctx.hasService(dev.duo.harness.tools.ConnectorStatusBoard.SERVICE_NAME)) {
                var connectors = root.putArray("connector");
                for (var entry : face.ctx.as(WebServiceViews.ConnectorStatus.class).connectorStatus().snapshot()) {
                    connectors.addObject().put("server", entry.server())
                            .put("state", entry.state()).put("detail", entry.detail());
                }
            }
            dev.duo.harness.agent.governance.ContextGovernance current = face.governance;
            if (current != null) {
                var occupancy = current.occupancy(tab.session);
                root.putObject("context")
                        .put("tokens", occupancy.tokens())
                        .put("thresholdTokens", occupancy.thresholdTokens())
                        .put("windowTokens", occupancy.windowTokens())
                        .put("fromProvider", occupancy.fromProvider())
                        // 压缩熔断态（M25 工单 05）：状态面可见——自动压缩暂停、会话照常
                        .put("compactionTripped", occupancy.compactionTripped());
            }
            // 后台任务区块（M23 工单 06）：注册表在场时列出本位发起（或无归属）的任务
            // （id/命令/状态/退出码），终态保留呈现（收敛可见）——CLI 侧任务不串显
            // （M23 工单 06 验收修正），注册表缺席零字段
            if (face.backgroundTasks != null) {
                var tasksNode = root.putArray("backgroundTasks");
                for (var task : face.backgroundTasks.all()) {
                    if (task.owner() != null && !WebPlugin.PRESENTER_ID.equals(task.owner())) {
                        continue;
                    }
                    var node = tasksNode.addObject()
                            .put("taskId", task.taskId())
                            .put("command", task.command())
                            .put("state", task.state().name());
                    if (task.isCompleted()) {
                        node.put("exitCode", task.exitCode());
                    }
                }
            }
            return root.toString();
        } catch (Exception e) {
            throw new IllegalStateException("状态面 JSON 构建失败", e);
        }
    }

    /** 读取 classpath 单页；缺失回退占位页（资源缺失显式可见不伪装白页）。 */
    private byte[] readClasspage() {
        byte[] page = WebHttp.readClassResource("/web/index.html");
        return page != null ? page
                : "<html><body><p>web/index.html 资源缺失</p></body></html>".getBytes(StandardCharsets.UTF_8);
    }

    private static String suffixOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
