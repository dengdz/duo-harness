package dev.duo.harness.agent.governance;

import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.session.Message;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.session.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文治理管线（M9，ADR-0007 v3 具名整期）：模型上下文构造时刻的读侧转换——
 * spill（超大工具结果卸载）→ 修剪（次长结果头尾收窄）→ microcompact（免模型本地
 * 裁剪旧工具结果，M25 工单 04）→ 计量 → compaction（远端历史折叠为摘要）。
 * **第一性约束：治理只影响"模型看到什么"，不影响"日志记了什么"**——入参出参都是
 * 投影消息，JSONL 日志永远完整，回放与审计语义不动摇。
 *
 * <p>顺序即协同（DSH 经验）：spill 先卸能卸的 → pruner 收窄次长的 → microcompact
 * 在逼近窗口时先试免模型裁剪 → 仍超预算才触发 compaction——前置环节可能让后置
 * 不必发生。行为阈值经 {@link Tuning} 注入（web/cli 装配的 yml {@code governance}
 * 段），字段缺省回退本类常量——不配置即缺省行为。summary 生成复用
 * {@link LlmAdapter} 直答形态。</p>
 *
 * <p>线程约定：实例非线程安全——随 agent 循环串行使用。</p>
 */
public final class ContextGovernance {

    private static final Logger logger = LoggerFactory.getLogger(ContextGovernance.class);

    /** spill 触发阈值（字符）：超大工具结果卸载落盘，给模型预览 + 定位符。 */
    public static final int SPILL_THRESHOLD_CHARS = 50_000;

    /** 修剪触发阈值（字符）：spill 后仍超长的结果头尾收窄。 */
    public static final int PRUNE_THRESHOLD_CHARS = 8_000;

    /** compaction 触发比例：修剪后估算 token 超窗口 × 此比例时折叠远端历史。 */
    public static final double COMPACTION_THRESHOLD_RATIO = 0.8;

    /** 模型上下文窗口（token）：按主流 128K 量级取常量，窗口异型时改此处。 */
    public static final long CONTEXT_WINDOW_TOKENS = 128_000;

    /** compaction 保留近端原文比例（对齐 DSH 保留 16% 的量级）。 */
    public static final double KEEP_RECENT_RATIO = 0.2;

    /** compaction 触发的最小远端消息数：太少没有折叠价值（近端之外寥寥数条）。 */
    static final int MIN_REMOTE_MESSAGES = 4;

    /**
     * microcompact 完整保留的最近组数（M25 工单 04，组 = 一条用户消息起的段落）——
     * ZCode keepRecent=5 同构：最近 N 组什么都不动，更早的只清白名单工具结果。
     */
    static final int MICROCOMPACT_KEEP_RECENT_GROUPS = 5;

    /**
     * microcompact 触发缓冲（token）：microcompact 阈值 = min(0.9 × 压缩阈值，
     * 压缩阈值 − 本值)——ZCode 同构公式，本地裁剪先于模型压缩触发，留出裁剪量的余地。
     */
    static final long MICROCOMPACT_BUFFER_TOKENS = 2_000;

    /** microcompact 触发比例（对压缩阈值的折让系数）。 */
    static final double MICROCOMPACT_RATIO = 0.9;

    /**
     * 压缩熔断阈值（M25 工单 05）：summary 压缩连续失败达本次数即熔断——自动压缩
     * 暂停（防"失败→重试→再失败"每轮空烧），会话照常可用；仅成功压缩清零，
     * manual /compact 不受熔断约束（ZCode 同构）。
     */
    static final int COMPACTION_FAILURE_TRIP_THRESHOLD = 3;

    /** spill 预览头长（字符）。 */
    static final int SPILL_PREVIEW_HEAD = 1_000;

    /** spill 预览尾长（字符）。 */
    static final int SPILL_PREVIEW_TAIL = 500;

    /** 修剪保留头长（字符）。 */
    static final int PRUNE_HEAD_CHARS = 2_000;

    /** 修剪保留尾长（字符）。 */
    static final int PRUNE_TAIL_CHARS = 1_000;

    /**
     * 治理阈值集（yml {@code governance} 段的绑定形态）：null 组件回退对应类常量缺省
     * ——段缺席或字段省略即缺省行为，装配零漂移。字段语义与常量一一对应。
     *
     * @param spillThresholdChars      spill 触发阈值（字符，正）
     * @param pruneThresholdChars      修剪触发阈值（字符，正）
     * @param compactionThresholdRatio compaction 触发比例（(0,1]）
     * @param contextWindowTokens      模型上下文窗口（token，正）
     * @param keepRecentRatio          （已停用）事件化压缩总结压缩点之前全部历史，无"近端保留"概念
     *                                 ——字段保留解析兼容既有 yml，值被忽略
     * @param minRemoteMessages        compaction 触发的最小远端消息数（正）
     * @param microcompactEnabled      microcompact 本地裁剪开关（M25 工单 04；缺省 true）
     * @param microcompactKeepRecent   microcompact 完整保留的最近组数（正；缺省 5）
     */
    public record Tuning(Integer spillThresholdChars, Integer pruneThresholdChars,
                         Double compactionThresholdRatio, Long contextWindowTokens,
                         Double keepRecentRatio, Integer minRemoteMessages,
                         Boolean microcompactEnabled, Integer microcompactKeepRecent) {
    }

    /**
     * 治理过程日志开关（默认关）：治理是实现内细节，逐轮打印会在呈现位对话流里
     * 刷屏——子代理后台长跑时尤甚（每次修剪/计量一行，数十轮即淹没父对话）。
     * 需要诊断时以 {@code -Dduo.governance.verbose=true} 开启。
     */
    private static final boolean VERBOSE = Boolean.getBoolean("duo.governance.verbose");

    /** 治理过程日志（开关控制；见 {@link #VERBOSE}）。 */
    private static void log(String message) {
        if (VERBOSE) {
            System.out.println("[上下文治理] " + message);
        }
    }

    private final LlmAdapter llm;
    private final int spillThresholdChars;
    private final int pruneThresholdChars;
    private final double compactionThresholdRatio;
    private final long contextWindowTokens;
    private final int minRemoteMessages;
    private final boolean microcompactEnabled;
    private final int microcompactKeepRecent;
    /** 压缩连续失败计数（M25 工单 05 熔断）；成功压缩清零。volatile：occupancy 自 HTTP 线程读。 */
    private volatile int compactionConsecutiveFailures;
    /** 压缩熔断态：true = 自动压缩暂停（manual /compact 与 microcompact 不受限）。 */
    private volatile boolean compactionTripped;

    public ContextGovernance(LlmAdapter llm) {
        this(llm, null);
    }

    public ContextGovernance(LlmAdapter llm, Tuning tuning) {
        this.llm = llm;
        this.spillThresholdChars = tuning == null || tuning.spillThresholdChars() == null
                ? SPILL_THRESHOLD_CHARS : tuning.spillThresholdChars();
        this.pruneThresholdChars = tuning == null || tuning.pruneThresholdChars() == null
                ? PRUNE_THRESHOLD_CHARS : tuning.pruneThresholdChars();
        this.compactionThresholdRatio = tuning == null || tuning.compactionThresholdRatio() == null
                ? COMPACTION_THRESHOLD_RATIO : tuning.compactionThresholdRatio();
        this.contextWindowTokens = tuning == null || tuning.contextWindowTokens() == null
                ? CONTEXT_WINDOW_TOKENS : tuning.contextWindowTokens();
        this.minRemoteMessages = tuning == null || tuning.minRemoteMessages() == null
                ? MIN_REMOTE_MESSAGES : tuning.minRemoteMessages();
        this.microcompactEnabled = tuning == null || tuning.microcompactEnabled() == null
                ? true : tuning.microcompactEnabled();
        this.microcompactKeepRecent = tuning == null || tuning.microcompactKeepRecent() == null
                ? MICROCOMPACT_KEEP_RECENT_GROUPS : tuning.microcompactKeepRecent();
    }

    /**
     * 治理管线入口：投影消息进、投影消息出——出参即"模型本轮看到的会话"。
     *
     * @param messages 会话投影（deriveMessages 产物，不可变列表）
     * @param session  所属会话（spill 落盘目录来源；microcompact 裁剪点与 compaction
     *                 压缩点经其 append 落痕）
     * @return 治理后的投影（可能原样返回——未触发任何治理时零开销透传）
     */
    public List<Message> govern(List<Message> messages, dev.duo.harness.session.Session session) {
        long estimate = ContextBudget.estimateMessageTokens(messages);
        // 计量口径（ADR-0009）：provider 真实用量优先——最近一次响应的 prompt+completion
        // 即本轮请求上下文的近似；事件缺失（provider 未报告/首轮）退回本地估算，治理不失效
        TokenUsage usage = latestUsage(session);
        long contextTokens = usage != null
                ? usage.promptTokens() + usage.completionTokens()
                : estimate;
        List<Message> governed = spillAndPrune(messages, session);
        // microcompact（M25 工单 04）：逼近窗口时先试免模型本地裁剪（事件痕 + 投影替换），
        // 降不动仍超压缩阈值才走 summary 压缩——裁剪零成本，先给免费的机会
        boolean microApplied = false;
        if (microcompactEnabled && contextTokens >= microcompactThreshold()) {
            List<Message> afterMicro = microcompact(governed, session);
            // 未触发时 microcompact 原引用透出（同引用 = 未生效信号，见其 javadoc）
            microApplied = afterMicro != governed;
            governed = afterMicro;
        }
        // compaction 计量：micro 生效时本轮改用裁剪后估算——usage 是裁剪前口径，
        // 沿用会让刚裁剪的会话立刻再触发一次昂贵的 summary 压缩（下轮真实 usage 自然接管）
        long compactionMeasure = microApplied
                ? ContextBudget.estimateMessageTokens(governed) : contextTokens;
        governed = compact(governed, contextThreshold(),
                compactionMeasure, !microApplied && usage != null, session);
        long after = ContextBudget.estimateMessageTokens(governed);
        if (after != estimate) {
            log(messages.size() + " 条消息：估算 "
                    + estimate + " → " + after + " tokens（会话 " + session.id() + "）");
        }
        return governed;
    }

    /**
     * microcompact 执行：选名单（豁免过滤见 {@link Microcompact}）→ 落裁剪点事件
     * （可回放可审计——JSONL 原文不动）→ 本轮投影立即替换（后续轮经投影层复现）。
     * 名单为空（低于最小节省）返回原引用不落事件——调用方以引用同一性判定是否生效。
     */
    private List<Message> microcompact(List<Message> messages, dev.duo.harness.session.Session session) {
        List<String> cleared = Microcompact.select(messages, toolMeta(session), microcompactKeepRecent);
        if (cleared.isEmpty()) {
            return messages;
        }
        long freedChars = 0;
        for (Message message : messages) {
            if (Message.Role.TOOL == message.role() && cleared.contains(message.toolCallId())) {
                freedChars += message.content() == null ? 0 : message.content().length();
            }
        }
        long freedTokens = freedChars / ContextBudget.CHARS_PER_TOKEN;
        session.append(SessionEvent.microcompact(cleared, freedTokens));
        log("microcompact：清除 " + cleared.size() + " 条旧工具结果，估算释放 " + freedTokens
                + " tokens（会话 " + session.id() + "）");
        List<Message> replaced = new ArrayList<>(messages);
        for (int i = 0; i < replaced.size(); i++) {
            Message message = replaced.get(i);
            if (Message.Role.TOOL == message.role() && cleared.contains(message.toolCallId())) {
                replaced.set(i, new Message(Message.Role.TOOL,
                        SessionEvent.MICROCOMPACT_CLEARED_MARKER, message.toolCallId(), null, null));
            }
        }
        return replaced;
    }

    /** callId → 工具元数据（名 + 失败标志；会话事件流单遍扫描）。 */
    private static Map<String, Microcompact.ToolMeta> toolMeta(dev.duo.harness.session.Session session) {
        Map<String, Microcompact.ToolMeta> meta = new HashMap<>();
        for (SessionEvent event : session.events()) {
            if (event.toolCallId() == null) {
                continue;
            }
            if (SessionEvent.TOOL_CALL.equals(event.type())) {
                meta.put(event.toolCallId(), new Microcompact.ToolMeta(event.toolName(), false));
            } else if (SessionEvent.TOOL_RESULT.equals(event.type()) && event.error()) {
                // 失败标志以结果事件为准（覆盖调用侧预置的 false）
                meta.put(event.toolCallId(), new Microcompact.ToolMeta(
                        meta.containsKey(event.toolCallId())
                                ? meta.get(event.toolCallId()).toolName() : event.toolName(), true));
            }
        }
        return meta;
    }

    /**
     * 上下文占用查询：与 compaction 判定同源的只读视图（状态面展示与阈值对照消费）。
     * 投影现算——任意时刻可查，不必等下一次请求构造。
     */
    public ContextOccupancy occupancy(dev.duo.harness.session.Session session) {
        TokenUsage usage = latestUsage(session);
        long tokens = usage != null
                ? usage.promptTokens() + usage.completionTokens()
                : ContextBudget.estimateMessageTokens(session.deriveMessages());
        return new ContextOccupancy(tokens, contextThreshold(), contextWindowTokens,
                usage != null, compactionTripped);
    }

    /** compaction 触发阈值（窗口 × 触发比例；窗口/比例均取生效调优值）。 */
    long contextThreshold() {
        return (long) (compactionThresholdRatio * contextWindowTokens);
    }

    /**
     * microcompact 触发阈值（M25 工单 04）：min(0.9 × 压缩阈值, 压缩阈值 − 2000)
     * ——ZCode 同构，本地裁剪恒先于模型压缩触发（裁剪零成本，先给免费的机会）。
     */
    long microcompactThreshold() {
        long base = contextThreshold();
        return Math.max(1, Math.min((long) (MICROCOMPACT_RATIO * base),
                base - MICROCOMPACT_BUFFER_TOKENS));
    }

    /** 压缩触发阈值（生效调优观测，仅装配断言用——状态面经 {@link #occupancy} 读取，不经此方法）。 */
    public long occupancyThresholdTokens() {
        return contextThreshold();
    }

    /** 生效上下文窗口（生效调优观测，仅装配断言用——状态面经 {@link #occupancy} 读取，不经此方法）。 */
    public long occupancyWindowTokens() {
        return contextWindowTokens;
    }

    /**
     * 最近一次带用量的 assistant/message 事件（倒查即得；续接的历史会话同样天然可取）。
     * 治理感知（M19，ADR-0020 决策 6 + M25 工单 05 扩展）：倒查先遇压缩点或
     * microcompact 裁剪点即返回 null——该实测值反映的是治理**前**的请求上下文，已
     * 不代表治理后的下一次请求（占用与计量回退本地估算，新一轮真实请求的用量事件
     * 落盘后自然恢复实测口径）。
     */
    private static TokenUsage latestUsage(dev.duo.harness.session.Session session) {
        List<SessionEvent> events = session.events();
        for (int i = events.size() - 1; i >= 0; i--) {
            SessionEvent event = events.get(i);
            // 压缩点与裁剪点同为计量断点（M25 工单 05）：两事件之后、新实测落盘之前，
            // 旧 usage 反映的是治理前口径——回退本地估算，防按虚高占用误触发
            if (SessionEvent.COMPACTION.equals(event.type())
                    || SessionEvent.MICROCOMPACT.equals(event.type())) {
                return null;
            }
            if (SessionEvent.ASSISTANT_MESSAGE.equals(event.type()) && event.usage() != null) {
                return event.usage();
            }
        }
        return null;
    }

    /** spill + 修剪：工具结果的体量治理——先卸能卸的（spill），再收窄次长的（修剪）。 */
    private List<Message> spillAndPrune(List<Message> messages, dev.duo.harness.session.Session session) {
        List<Message> result = messages;
        for (int i = 0; i < result.size(); i++) {
            Message message = result.get(i);
            if (message.role() != Message.Role.TOOL
                    || message.content() == null) {
                continue;
            }
            int length = message.content().length();
            if (length > spillThresholdChars) {
                String replacement = spill(message, session);
                if (replacement == null) {
                    continue; // 卸载失败保留原结果（治理永不丢数据）
                }
                if (result == messages) {
                    result = new ArrayList<>(messages);
                }
                result.set(i, new Message(Message.Role.TOOL, replacement,
                        message.toolCallId(), null, null));
            } else if (length > pruneThresholdChars) {
                if (result == messages) {
                    result = new ArrayList<>(messages);
                }
                result.set(i, new Message(Message.Role.TOOL, prune(message.content()),
                        message.toolCallId(), null, null));
            }
        }
        return result;
    }

    /** 修剪：头 2K + 标注 + 尾 1K（次长结果的体量收窄；原文仍在 JSONL）。 */
    private String prune(String content) {
        int middle = content.length() - PRUNE_HEAD_CHARS - PRUNE_TAIL_CHARS;
        log("工具结果 " + content.length() + " 字符超 "
                + pruneThresholdChars + "，修剪中段 " + middle + " 字符");
        return content.substring(0, PRUNE_HEAD_CHARS)
                + "\n…[已修剪中段 " + middle + " 字符，完整原文在会话日志中]…\n"
                + content.substring(content.length() - PRUNE_TAIL_CHARS);
    }

    /** 单条 spill：原文落盘，返回"预览 + 定位符"替换文本；失败返回 null。 */
    private String spill(Message message, dev.duo.harness.session.Session session) {
        try {
            Path spillDir = session.jsonl().getParent()
                    .resolve(session.id()).resolve("spill");
            Files.createDirectories(spillDir);
            int sequence = nextSequence(spillDir);
            // 投影的 TOOL 消息不带工具名（只携 toolCallId）——文件名以调用 id 标识
            String callId = message.toolCallId() == null ? "call" : message.toolCallId();
            Path file = spillDir.resolve(sequence + "-" + sanitize(callId) + ".txt");
            Files.writeString(file, message.content());
            String content = message.content();
            String preview = content.substring(0, SPILL_PREVIEW_HEAD) + "\n…[中间 "
                    + (content.length() - SPILL_PREVIEW_HEAD - SPILL_PREVIEW_TAIL) + " 字符已卸载]…\n"
                    + content.substring(content.length() - SPILL_PREVIEW_TAIL);
            log("工具结果 " + content.length() + " 字符超 "
                    + spillThresholdChars + "，已卸载 " + file);
            return preview + "\n[完整原文已落盘: " + file + "，需要更多内容时请向用户询问该文件路径]";
        } catch (IOException e) {
            log("卸载失败，保留原结果: " + e.getMessage());
            return null;
        }
    }

    /** spill 目录内下一个序号（按现有文件数递增）。 */
    private int nextSequence(Path spillDir) throws IOException {
        try (var list = Files.list(spillDir)) {
            return (int) list.count() + 1;
        }
    }

    /** 文件名安全化：工具名只留单词字符。 */
    private static String sanitize(String toolName) {
        return toolName.replaceAll("[^\\w.-]", "_");
    }

    /** 无会话纯变换（测试/无落盘场景专用）：不落压缩点事件——生产路径经 {@link #govern} 走事件版。 */
    List<Message> compact(List<Message> messages, long thresholdTokens) {
        return compact(messages, thresholdTokens,
                ContextBudget.estimateMessageTokens(messages), false, null);
    }

    /**
     * compaction（ADR-0020 决策 6 事件化）：计量超阈值时，**压缩点之前的全部历史**折叠为
     * 固定骨架摘要并落 {@code context/compacted} 压缩点事件（触发方式 auto），返回值 =
     * 替换头单条——与投影语义（最后压缩点之前以总结替换、之后照常）严格一致，当轮请求
     * 与后续轮重放模型视角无差别。事件化后不再每轮重复总结（旧请求期纯变换每轮重复
     * 调用 LLM）。session 为 null 时退化为纯变换（不落盘）。历史不足最小折叠量时放弃；
     * 熔断态静默跳过；LLM 失败或空摘要计一次连续失败并原样透出（降级不冒险、不落
     * 事件——空摘要落盘会令投影坍缩为空），连续失败达
     * {@link #COMPACTION_FAILURE_TRIP_THRESHOLD} 即熔断。
     *
     * @param measuredTokens       计量值：provider 真实用量或本地估算（由 measuredFromProvider 标注口径）
     * @param measuredFromProvider 计量是否来自 provider 真实用量（日志口径标注）
     * @param session              压缩点事件落点（null = 纯变换不落盘）
     */
    List<Message> compact(List<Message> messages, long thresholdTokens,
                          long measuredTokens, boolean measuredFromProvider,
                          dev.duo.harness.session.Session session) {
        if (measuredTokens <= thresholdTokens || messages.size() < minRemoteMessages) {
            return messages;
        }
        if (compactionTripped) {
            // 熔断态（M25 工单 05）：自动压缩静默跳过，会话照常——状态面经 occupancy 可见
            log("压缩熔断中，跳过自动压缩（已连续失败 " + compactionConsecutiveFailures + " 次）");
            return messages;
        }
        try {
            String summary = summarize(messages);
            // 空摘要与异常同计入失败（M25 工单 05 审查修复）：LLM 可达但持续返空白
            // 同样是"每轮空烧"路径，熔断必须覆盖
            if (summary.isBlank()) {
                return compactionFailed(messages, null);
            }
            log((measuredFromProvider ? "实测" : "估算") + " "
                    + measuredTokens + " tokens 超阈值 " + thresholdTokens
                    + "，历史 " + messages.size() + " 条折叠为摘要");
            if (session != null) {
                session.append(SessionEvent.compaction(summary, TRIGGER_AUTO));
            }
            compactionConsecutiveFailures = 0;
            compactionTripped = false;
            return List.of(summaryHead(summary));
        } catch (Exception e) {
            return compactionFailed(messages, e);
        }
    }

    /**
     * 压缩失败的统一收敛：计连续失败数、达阈值置熔断（warn 留痕一次）、原样透出。
     * 熔断置位后自动路径不再触达本方法（{@code compact()} 入口早退）。
     */
    private List<Message> compactionFailed(List<Message> messages, Exception cause) {
        compactionConsecutiveFailures++;
        if (compactionConsecutiveFailures >= COMPACTION_FAILURE_TRIP_THRESHOLD) {
            compactionTripped = true;
            logger.warn("压缩连续失败 {} 次，熔断：自动压缩暂停（会话照常，"
                    + "manual /compact 不受限，成功压缩后自动恢复）",
                    compactionConsecutiveFailures, cause);
        } else {
            logger.warn("压缩摘要生成失败（{}/{}），本次请求原样透出",
                    compactionConsecutiveFailures, COMPACTION_FAILURE_TRIP_THRESHOLD, cause);
        }
        return messages;
    }

    /**
     * 手动压缩（/compact 命令的本体，M19）：不看阈值强制压缩并落压缩点事件（触发方式
     * manual）。语义与 {@link #compact} 一致：压缩点之前的全部历史折叠为摘要（投影随后
     * 即以此拼接，无"近端保留"）。历史不足最小折叠量时提示无需压缩、不落事件；
     * 空摘要（LLM 返回空白）不落事件——空压缩点会让投影坍缩为空，宁可不动。
     * 命令语义 busySafe=false——调用方保证 agent 空闲（动上下文结构必须 idle）。
     *
     * @return 回显摘要（压缩结果或无需压缩/失败的说明）
     */
    public String compactNow(dev.duo.harness.session.Session session) {
        List<Message> projected = session.deriveMessages();
        if (projected.size() < minRemoteMessages) {
            return "历史不足 " + minRemoteMessages + " 条消息，无需压缩。";
        }
        long before = ContextBudget.estimateMessageTokens(projected);
        String summary = summarize(projected);
        if (summary.isBlank()) {
            log("手动压缩放弃：摘要生成为空，不落压缩点");
            return "摘要生成失败（LLM 返回为空），未压缩——请稍后重试。";
        }
        // manual /compact 不受熔断约束（ZCode 同构）：用户显式指令优先于熔断保护，
        // 成功即清计数并解除熔断
        session.append(SessionEvent.compaction(summary, TRIGGER_MANUAL));
        compactionConsecutiveFailures = 0;
        compactionTripped = false;
        long after = ContextBudget.estimateMessageTokens(List.of(summaryHead(summary)));
        log("手动压缩：历史 " + projected.size() + " 条折叠为摘要，估算 "
                + before + " → " + after + " tokens（会话 " + session.id() + "）");
        return "已压缩：" + projected.size() + " 条历史消息折叠为摘要（估算 "
                + before + " → " + after + " tokens），后续请求按压缩点拼接。";
    }

    /** 投影替换头（常量与 session 投影同源——两侧逐字同文由单一事实来源保证）。 */
    private static Message summaryHead(String summary) {
        return new Message(Message.Role.USER,
                SessionEvent.COMPACTION_SUMMARY_HEADER + summary, null, null, null);
    }

    /** 压缩触发方式（context/compacted 事件的 toolName 位）：/compact 命令。 */
    public static final String TRIGGER_MANUAL = "manual";

    /** 压缩触发方式：预算阈值触发。 */
    public static final String TRIGGER_AUTO = "auto";

    /** compaction 摘要生成：压缩点之前的全部消息经 LLM 直答折叠为固定骨架摘要。 */
    String summarize(List<Message> remote) {
        // 辅助性请求强制 low 档（M24 工单 10，ADR-0026 决策六「标题生成等」）——
        // 摘要是内部折叠件，不随用户 /effort high 档烧大钱
        ChatRequest request = new ChatRequest(SUMMARY_SYSTEM, toChatMessages(remote), List.of(),
                dev.duo.harness.llm.LlmConfig.EFFORT_LOW);
        StringBuilder summary = new StringBuilder();
        llm.stream(request, (ChatChunk chunk) -> summary.append(chunk.text()));
        return summary.toString();
    }

    /** 投影 → llm 消息（摘要调用用 USER 角色平铺——只取文本，协议形态无关紧要）。 */
    private static List<ChatMessage> toChatMessages(List<Message> messages) {
        List<ChatMessage> result = new ArrayList<>();
        for (Message message : messages) {
            String prefix = switch (message.role()) {
                case USER -> "用户：";
                case ASSISTANT -> "助手：";
                case TOOL -> "工具(" + message.toolCallId() + ")：";
            };
            result.add(new ChatMessage(ChatMessage.Role.USER,
                    prefix + message.content(), null, null));
        }
        return result;
    }

    /** 摘要生成的固定骨架（对齐 DSH checkpoint 思路：结构固定便于模型稳定产出）。 */
    static final String SUMMARY_SYSTEM =
            "你是会话压缩器。把给定历史对话折叠为一份摘要，严格使用以下四个小节（markdown 二级标题），"
                    + "不添加其他内容、不发明未出现的事实：\n"
                    + "## 主要请求\n## 关键结论\n## 已做操作\n## 未决事项";
}
