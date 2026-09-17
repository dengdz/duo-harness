package dev.duo.harness.agent.subagent;

import dev.duo.harness.agent.subagent.backend.SubagentBackend;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子代理管理者（ADR-0015 决策 2/3）：spawn/fork 的登记与生命周期单点——
 * 建独立子会话（进程级锁语义与会话域一致，存放于会话目录的 {@code subagents}
 * 子目录、侧栏列表天然排除）、父会话写引用事件、后台虚拟线程跑任务、完成把
 * 最终回答经 {@code subagent/completed} 回流父会话（父聚合结果的数据源）。
 *
 * <p>spawn/fork 立即返回 agent id（后台异步模型）——同步等待不做，治理经控制面
 * （{@link #sendMessage} 运行中纠偏/空闲续轮、{@link #interrupt} 中止、
 * {@link #all} 状态列表）。fork 另播父日志平衡完成轮前缀（{@link SeedSlicer}）
 * 并落种子边界。</p>
 *
 * <p>线程模型：子会话的单写者是子代理后台虚拟线程（控制面的纠偏 append 与
 * 中止痕迹 append 是并发安全的跨线程写入——persist 持锁保护，设计内行为）；
 * 终局回流单点收尾（{@link #finish}）：状态落定、子会话痕迹与锁释放、父会话
 * 终局回流都只在后台线程的收尾段发生，控制面只置标志与打断。父会话关闭后的
 * 回流失败不炸后台线程（容错降级为仅子会话留痕）。</p>
 */
public final class SubagentManager {

    /** 服务名（SubagentPlugin 模板非空时发布；呈现位装配经视图寻址）。 */
    public static final String SERVICE_NAME = "subagents";

    /** 子会话在会话目录下的存放子目录（Session.list 非递归 → 侧栏天然排除）。 */
    public static final String SUBDIRECTORY = "subagents";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 终局回流文本的结构标记（拼装与 CLI/呈现解析共用一份词汇，措辞变更须同步）。 */
    public static final String FINAL_ANSWER_MARKER = "最终回答：";
    public static final String PARTIAL_RESULTS_MARKER = "已完成的中间成果：";
    public static final String EXCERPT_MARKER = "末次结果摘录：";

    /**
     * 子代理状态（list_agents 四态）：运行中 / 空闲（正常完成待命，可续轮——
     * DSH idle 语义）/ 失败（异常结束，可续轮重试）/ 中断（被中止，终态不可续）。
     */
    public enum State { RUNNING, IDLE, FAILED, INTERRUPTED }

    /** 注册表条目：id 即子会话文件名，控制面与呈现按 id 寻址。 */
    public static final class Entry {

        private final String id;
        private final String templateName;
        private final Path jsonl;
        private final Session parentSession;
        private final String parentSessionId;
        private volatile Session childSession;
        private volatile Thread worker;
        private volatile State state = State.RUNNING;

        Entry(String id, String templateName, Path jsonl, Session parentSession) {
            this.id = id;
            this.templateName = templateName;
            this.jsonl = jsonl;
            this.parentSession = parentSession;
            this.parentSessionId = parentSession.id();
        }

        /** 父会话（completed 回流的落点；跨线程写经 persist 锁保护）。 */
        Session parentSession() {
            return parentSession;
        }

        /** 子代理 id（同子会话 id）。 */
        public String id() {
            return id;
        }

        /** 模板名。 */
        public String templateName() {
            return templateName;
        }

        /** 当前状态（运行中 / 空闲 / 失败 / 中断）。 */
        public State state() {
            return state;
        }

        /** 子会话文件（完成后可打开回放全程）。 */
        public Path jsonl() {
            return jsonl;
        }

        /** 创建本子代理的父会话 id（控制面的会话归属校验用）。 */
        public String parentSessionId() {
            return parentSessionId;
        }
    }

    private final SubagentTemplates templates;
    private final Map<String, Entry> agents = new ConcurrentHashMap<>();

    public SubagentManager(SubagentTemplates templates) {
        this.templates = templates;
    }

    /**
     * spawn：全新子代理。立即返回 id，子任务在后台虚拟线程运行。
     *
     * @throws PluginException 模板名未配置或后端未绑定（经工具管线收敛为错误结果）
     */
    public String spawn(Session parentSession, String templateName, String taskDescription) {
        backend(); // fail-loud：后端未绑定时在派生入口即点名，不等到后台线程才失败
        return launch(parentSession, templateName, taskDescription, false);
    }

    /** fork：同 spawn，另播父日志平衡完成轮前缀为子会话开头段（DSH 同款播种）。 */
    public String fork(Session parentSession, String templateName, String taskDescription) {
        backend();
        return launch(parentSession, templateName, taskDescription, true);
    }

    /**
     * 控制面 send_message：运行中纠偏（指示写入子会话——子 agent 下一轮投影自然
     * 带上，留痕可审计）；空闲/失败则开新轮（同一子会话重新持锁再跑，指示即新轮
     * 任务）；中断是终态，拒绝。
     *
     * @return 给模型看的执行确认（下一轮生效语义 / 新轮启动）
     * @throws PluginException agentId 不存在或状态不可达（经工具管线收敛为错误结果）
     */
    public String sendMessage(String agentId, String message) {
        Entry entry = requireEntry(agentId);
        State state = entry.state;
        switch (state) {
            case RUNNING -> {
                entry.childSession.append(SessionEvent.userMessage("[父补充指示] " + message));
                return "指示已送达运行中的子代理 " + agentId + "，将在其下一轮生效。";
            }
            case IDLE, FAILED -> {
                Session child;
                try {
                    child = Session.load(entry.jsonl);
                } catch (dev.duo.harness.session.SessionLockedException e) {
                    // 窄窗口竞态：终局收尾已置空闲、锁尚未释放——指示未送达，稍后重试即可
                    throw new PluginException(
                            "子代理 " + agentId + " 正在收尾，请稍后重试。");
                }
                entry.childSession = child;
                entry.state = State.RUNNING;
                SubagentTemplate template = templates.byName(entry.templateName).orElseThrow();
                Thread worker = Thread.ofVirtual().name("subagent-" + agentId + "-round").start(() ->
                        finish(new SubagentBackend.Task(agentId, template, message, child), entry));
                entry.worker = worker;
                return "子代理 " + agentId + " 已空闲，指示已作为新任务开启新一轮（后台运行中）。";
            }
            case INTERRUPTED -> throw new PluginException(
                    "子代理 " + agentId + " 已被中止（终态），不能再接收指示；请 spawn 新的子代理。");
            default -> throw new PluginException("子代理 " + agentId + " 当前状态不可接收指示: " + state);
        }
    }

    /**
     * 控制面 interrupt_agent：置中断标志并打断后台线程（协作式中止——阻塞 IO 处
     * 最有效）。终局收尾单点在后台线程（{@link #finish}）：子会话留
     * {@code subagent/interrupted} 痕迹、父会话回流"已被中止"。
     *
     * @throws PluginException agentId 不存在或已结束（无运行态可中止）
     */
    public String interrupt(String agentId) {
        Entry entry = requireEntry(agentId);
        if (entry.state != State.RUNNING) {
            throw new PluginException("子代理 " + agentId + " 当前不在运行中（" + entry.state + "），无需中止。");
        }
        entry.state = State.INTERRUPTED;
        Thread worker = entry.worker;
        if (worker != null) {
            worker.interrupt();
        }
        return "已向子代理 " + agentId + " 发出中止（协作式中止，终局痕迹随后落入子会话与父会话）。";
    }

    /** 全部子代理条目（注册快照，控制面 list 的数据源）。 */
    public List<Entry> all() {
        return List.copyOf(agents.values());
    }

    /** 按 id 查寻（控制面寻址入口）。 */
    public Optional<Entry> byId(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    /** 某父会话名下的全部子代理（list_agents 的呈现边界——长驻呈现位换绑后互不可见）。 */
    public List<Entry> byParentSession(String parentSessionId) {
        return agents.values().stream()
                .filter(e -> e.parentSessionId().equals(parentSessionId))
                .toList();
    }

    /** 可用模板名清单（spawn/fork 的工具描述与 schema enum 的取数源——模型第一次就能点对名）。 */
    public List<String> templateNames() {
        return templates.all().stream().map(SubagentTemplate::name).toList();
    }

    /**
     * 绑定执行后端：呈现位装配时构造 backend 后调用一次（spawn/fork 与后续的
     * send_message 续轮共用同一后端；跨 harness 替换时整体换装）。绑定前任何
     * 派生尝试点名失败——装配缺口的 fail-loud。
     */
    public void bindBackend(SubagentBackend backend) {
        if (backend == null) {
            throw new PluginException("子代理执行后端不能为空");
        }
        this.backendRef = backend;
    }

    private SubagentBackend backendRef;

    private SubagentBackend backend() {
        if (backendRef == null) {
            throw new PluginException("子代理执行后端未绑定（呈现位装配缺失）");
        }
        return backendRef;
    }

    private String launch(Session parentSession, String templateName,
                          String taskDescription, boolean seed) {
        SubagentTemplate template = templates.byName(templateName)
                .orElseThrow(() -> new PluginException("未知子代理模板: " + templateName));
        Path subagentsDir = parentSession.jsonl().getParent().resolve(SUBDIRECTORY);
        Session child = Session.create(subagentsDir);
        String agentId = child.id();

        parentSession.append(SessionEvent.subagentSpawned(agentId, templateName, spawnPayload(
                parentSession.id(), taskDescription, seed)));
        if (seed) {
            List<SessionEvent> prefix = SeedSlicer.balancedCompletedRounds(parentSession.events());
            for (SessionEvent event : prefix) {
                child.append(event); // 播种原样落子日志（复用事件类型的投影形态，ADR-0015 决策 4）
            }
            child.append(SessionEvent.subagentSeedBoundary(parentSession.id(), prefix.size()));
        }

        Entry entry = new Entry(agentId, templateName, child.jsonl(), parentSession);
        entry.childSession = child;
        agents.put(agentId, entry);
        Thread worker = Thread.ofVirtual().name("subagent-" + agentId).start(() ->
                finish(new SubagentBackend.Task(agentId, template, taskDescription, child), entry));
        entry.worker = worker;
        return agentId;
    }

    /**
     * 后台执行体与终局单点收尾：跑任务 → 按条目状态归档（INTERRUPTED 优先——
     * 中止标志由控制面在任意时刻置入，收尾统一兑现）→ 子会话痕迹与锁释放 →
     * 父会话终局回流（父已关闭则降级为仅子会话留痕）。
     */
    private void finish(SubagentBackend.Task task, Entry entry) {
        SubagentBackend.Outcome outcome;
        try {
            outcome = backend().run(task);
        } catch (Exception e) {
            boolean wasInterrupted = entry.state == State.INTERRUPTED;
            outcome = new SubagentBackend.Outcome(null, false, wasInterrupted
                    ? "被父 agent 中止"
                    : "子任务执行失败: " + e.getMessage());
        }
        boolean aborted = entry.state == State.INTERRUPTED;
        boolean healthy = outcome.completed() && !aborted;
        if (aborted) {
            try {
                task.session().append(SessionEvent.subagentInterrupted(task.agentId(), "被父 agent 中止"));
            } catch (RuntimeException ignored) {
                // 中止痕迹尽力落盘（会话已关闭等异常不阻断收尾）
            }
        }
        entry.state = healthy ? State.IDLE : (aborted ? State.INTERRUPTED : State.FAILED);
        String summary;
        if (healthy) {
            summary = "子代理 " + task.agentId() + "（模板 " + entry.templateName() + "）已完成。\n"
                    + FINAL_ANSWER_MARKER + "\n"
                    + outcome.finalAnswer();
        } else if (aborted) {
            summary = "子代理 " + task.agentId() + "（模板 " + entry.templateName() + "）已被中止：未产出结果。";
        } else {
            // 未完成但不等于无成果：带上子代理已完成的工作（工具调用摘要）与续轮指引——
            // 父 agent 据此可自行汇总已有信息，或经 send_message 让该子代理接着干
            summary = "子代理 " + task.agentId() + "（模板 " + entry.templateName() + "）未正常完成："
                    + outcome.failure()
                    + (outcome.finalAnswer() == null || outcome.finalAnswer().isBlank()
                            ? "" : "\n" + PARTIAL_RESULTS_MARKER + "\n" + outcome.finalAnswer())
                    + "\n（如需继续，可用 send_message 给该子代理更多指示——它将带着已有上下文续跑）";
        }
        try {
            entry.parentSession().append(SessionEvent.subagentCompleted(task.agentId(), summary));
        } catch (RuntimeException ignored) {
            // 父会话已关闭（/exit 后子代理才完成）：降级为仅子会话留痕，不炸后台线程
        } finally {
            task.session().close(); // 释放锁——完成后可被打开查看全程（ADR-0015 决策 3）
        }
    }

    /** spawned 事件载荷 JSON（text 可选位；会话层透明往返，呈现卡片与审计的取数源）。 */
    private static String spawnPayload(String parentSessionId, String taskDescription, boolean fork) {
        JsonNode payload = JSON.createObjectNode()
                .put("task", taskDescription)
                .put("mode", fork ? "fork" : "spawn")
                .put("parentSessionId", parentSessionId);
        return payload.toString();
    }

    private Entry requireEntry(String agentId) {
        return byId(agentId).orElseThrow(() -> new PluginException("子代理不存在: " + agentId));
    }
}
