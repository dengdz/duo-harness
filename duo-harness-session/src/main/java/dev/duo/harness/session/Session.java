package dev.duo.harness.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 会话：一条只追加的事件日志（事件溯源）。
 *
 * <p>{@link #append} 是唯一写入原语——内存追加与 JSONL 同步落盘同时发生，
 * 崩溃最多丢正在写的一条。对话历史由 {@link #deriveMessages()} 从日志投影派生：
 * `user/message` 与 `assistant/message` 入列，流式 chunk 是过程细节不投影。
 * 会话是中立数据基座——多轮记忆、持久化回放等消费方都基于同一份日志。</p>
 *
 * <p>会话身份在文件名：{@code ~/.duo/sessions/<id>.jsonl}，id = 启动时间 + 短随机后缀，
 * 人类可读；同秒内以后缀字典序区分——后缀 4 位十六进制补零，保证字典序与生成序一致。</p>
 *
 * <p>线程约定：单写者（append 只在会话属主的执行线程上串行调用）；读侧
 * {@link #events()} 返回快照、{@link #addListener} 用 CoW——读取与追加并发安全
 * （M8 起 Web SSE 回放与 agent 流式追加并发是常态）。close 的线程约定与单写者相反：
 * 它通常在 HTTP / 调度线程上执行（换绑、停止服务），与 append 线程并发——原子标志保证
 * 幂等，append 在关闭后立即失败。</p>
 *
 * <p><b>独占语义</b>：打开会话即取得 JSONL 文件的进程级独占锁，持有至
 * {@link #close()}——同一会话被第二个进程（或本进程第二实例）打开时抛
 * {@link SessionLockedException}，把"两个进程各写各的内存视图、日志交错追加"
 * 的静默分脑变为打开即失败。换绑到其他会话、进程退出前应 close 释放。</p>
 */
public final class Session {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ID_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final String id;
    private final Path jsonl;
    private final List<SessionEvent> events = new ArrayList<>();
    /**
     * 共享不可变快照（CoW，ADR-0014）：{@code events} 在 persist / load 追加后锁内
     * （load 为构造期单线程）重建，读侧 {@link #events()} 零拷贝返回此引用——读多写少
     * 的消费形态（治理每轮、状态面轮询、回放分页）不再每次付出 O(n) 拷贝，写侧每次
     * 追加一次重建由 append-only 的低频写承担。
     */
    private volatile List<SessionEvent> snapshot = List.of();
    /** 事件监听器（CoW：回调中注销不破坏遍历）。 */
    private final List<BiConsumer<Integer, SessionEvent>> listeners = new CopyOnWriteArrayList<>();
    /** 独占锁的文件通道（持有至 {@link #close()}）。 */
    private final FileChannel lockChannel;
    /** 会话文件的独占锁（进程级单写者检测）。 */
    private final FileLock fileLock;
    /** 已关闭标志（close 幂等）。 */
    private final AtomicBoolean closed =
            new AtomicBoolean(false);
    /** 本实例的锁注册键（绝对规范化路径；close 时注销）。 */
    private final Path lockKey;

    private Session(String id, Path jsonl, FileChannel lockChannel,
                    FileLock fileLock, Path lockKey) {
        this.id = id;
        this.jsonl = jsonl;
        this.lockChannel = lockChannel;
        this.fileLock = fileLock;
        this.lockKey = lockKey;
    }

    /** 新建会话：生成 id、创建 JSONL 文件并取得独占锁。 */
    public static Session create(Path sessionsDir) {
        String id = newId();
        Path file = sessionsDir.resolve(id + ".jsonl");
        try {
            Files.createDirectories(sessionsDir);
            Files.createFile(file);
        } catch (IOException e) {
            throw new PluginException("无法创建会话文件: " + file, e);
        }
        return lock(id, file);
    }

    /**
     * 从 JSONL 重放读回会话（文件必须存在且为合法会话日志）。
     *
     * @throws SessionLockedException 文件已被本进程另一实例或其他进程占用
     */
    public static Session load(Path jsonl) {
        String id = jsonl.getFileName().toString();
        if (id.endsWith(".jsonl")) {
            id = id.substring(0, id.length() - ".jsonl".length());
        }
        Session session = lock(id, jsonl);
        try {
            // 经持锁通道读取全文再按行解析——不另开 fd（POSIX 语义：进程关闭同一文件的
            // 任意 fd 会释放它在该文件上的全部锁，独占锁会被自己的读取路径放掉）
            session.lockChannel.position(0);
            String content = readAll(session.lockChannel);
            for (String line : content.split("\n", -1)) {
                if (line.isBlank()) {
                    continue;
                }
                session.events.add(parse(line));
            }
            session.snapshot = List.copyOf(session.events); // 构造期单线程：返回前建初始快照
        } catch (IOException e) {
            session.close(); // 读取失败即释放锁，不留半开状态
            throw new PluginException("会话文件读取失败: " + jsonl, e);
        }
        return session;
    }

    /**
     * 取得会话文件独占锁并构造实例：**单写者检测**——同进程第二实例由
     * OverlappingFileLockException 拒绝，跨进程由 tryLock 返回 null 拒绝
     * （锁是协商式：别人不用锁硬写仍能写，本机制防的是"双方都以为自己独占"）。
     *
     * @throws SessionLockedException 锁已被占用
     * @throws PluginException        文件无法打开（权限、路径等）
     */
    /**
     * JVM 内已持锁会话注册表（绝对路径 → 持有标记）。同进程第二实例在**打开 fd 之前**
     * 即被拒绝——若先 open 再 tryLock，失败路径关闭探测 fd 会触发 POSIX 陷阱：
     * 进程关闭同一文件的任意 fd，内核会释放该进程在此文件上的**全部**锁（包括
     * 已成功持锁实例的锁），跨进程独占就此蒸发（M10-03 验收实测踩中）。
     */
    private static final java.util.concurrent.ConcurrentMap<Path, Boolean> HELD_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 注册表检查 → open → tryLock → put 全程的进程内串行闸（与 {@link #isOccupied} 共享）：
     * tryLock 成功到 put 之间的间隙若并发探测线程 open+close 同文件 fd，POSIX 陷阱会把
     * 刚拿到的锁一并蒸发——低概率、后果是独占保护失效，故以串行化根除（M13-05 审查发现）。
     */
    private static final Object LOCK_GATE = new Object();

    private static Session lock(String id, Path jsonl) {
        Path key = jsonl.toAbsolutePath().normalize();
        synchronized (LOCK_GATE) {
            if (HELD_LOCKS.containsKey(key)) {
                throw new SessionLockedException(id, jsonl);
            }
            FileChannel channel = null;
            try {
                channel = FileChannel.open(jsonl,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
                FileLock fileLock = channel.tryLock();
                if (fileLock == null) {
                    closeQuietly(channel); // 他进程持锁：关自己的探测 fd 无碍（锁在别人名下）
                    throw new SessionLockedException(id, jsonl);
                }
                HELD_LOCKS.put(key, Boolean.TRUE);
                return new Session(id, jsonl, channel, fileLock, key);
            } catch (OverlappingFileLockException e) {
                // 注册表已拦同进程重复；此分支仅防外部路径竞态，防御性保留
                closeQuietly(channel);
                throw new SessionLockedException(id, jsonl, e);
            } catch (IOException e) {
                closeQuietly(channel);
                throw new PluginException("会话文件打开失败: " + jsonl, e);
            }
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // 打开失败路径上的清理，忽略
            }
        }
    }

    /**
     * 占用探测（工单 M13-05，只读）：会话是否已被持有——本进程查持锁注册表即得，
     * 他进程经真实 {@code tryLock} 失败判定（探测锁随探测通道关闭而释放）。**不重复
     * open 本进程已持锁的会话**：探测 fd 的关闭会触发 POSIX 释放陷阱（关闭同文件
     * 任意 fd 释放本进程全部锁），注册表短路同时保证了这一点。文件打不开（不存在、
     * 权限等）按未占用返回——标注只提供预期，占用与否的最终裁决仍是换绑时的独占锁。
     */
    public static boolean isOccupied(Path jsonl) {
        Path key = jsonl.toAbsolutePath().normalize();
        synchronized (LOCK_GATE) {
            if (HELD_LOCKS.containsKey(key)) {
                return true;
            }
            FileChannel channel = null;
            try {
                channel = FileChannel.open(jsonl, StandardOpenOption.READ, StandardOpenOption.WRITE);
                FileLock probe = channel.tryLock();
                return probe == null;
            } catch (OverlappingFileLockException e) {
                return true; // 注册表已拦同进程重复；此为外部竞态兜底
            } catch (IOException e) {
                return false;
            } finally {
                closeQuietly(channel);
            }
        }
    }

    /**
     * 关闭会话：释放独占锁与文件通道、摘除全部事件监听器（幂等）。本进程不再独占该会话，
     * 其他进程与实例可重新打开；调用方应在会话生命周期结束时调用（Web 停止、CLI 退出、
     * 换绑到其他会话时）。关闭后写入与订阅均失效——应停止使用本实例。
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        HELD_LOCKS.remove(lockKey);
        try {
            fileLock.release();
        } catch (IOException ignored) {
            // 通道关闭会连带释放，忽略
        }
        closeQuietly(lockChannel);
        listeners.clear();
    }

    /** 目录内最近活动的会话（按文件修改时间，即最后被创建/写入的）；无会话返回 null。 */
    /** 会话摘要（M8 会话侧栏数据源：id + 最近修改时间）。 */
    public record SessionSummary(String id, Path jsonl, long lastModifiedMs) {

        /** 构造时校验非空——错误前移到构造点。 */
        public SessionSummary {
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(jsonl, "jsonl");
        }
    }

    /**
     * 列出目录下全部会话（按最近修改时间倒序；M8 会话侧栏数据源）。
     *
     * @param sessionsDir 会话目录（不存在或为空时返回空列表）
     * @return 会话摘要列表
     */
    public static List<SessionSummary> list(Path sessionsDir) {
        record Entry(String id, Path jsonl, long ms) { }
        List<Entry> entries = new ArrayList<>();
        if (Files.isDirectory(sessionsDir)) {
            try (var list = Files.list(sessionsDir)) {
                for (Path path : list.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                    try {
                        entries.add(new Entry(
                                path.getFileName().toString().replace(".jsonl", ""),
                                path, Files.getLastModifiedTime(path).toMillis()));
                    } catch (IOException ignored) {
                        // 单个文件元数据读取失败跳过
                    }
                }
            } catch (IOException e) {
                throw new PluginException("会话目录遍历失败: " + sessionsDir, e);
            }
        }
        return entries.stream()
                .sorted((a, b) -> Long.compare(b.ms(), a.ms()))
                .map(e -> new SessionSummary(e.id(), e.jsonl(), e.ms()))
                .toList();
    }

    public static Session latest(Path sessionsDir) {
        Path latest = null;
        FileTime latestTime = null;
        if (Files.isDirectory(sessionsDir)) {
            List<Path> files;
            try (var list = Files.list(sessionsDir)) {
                files = list.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList();
            } catch (IOException e) {
                throw new PluginException("会话目录遍历失败: " + sessionsDir, e);
            }
            for (Path path : files) {
                try {
                    FileTime time = Files.getLastModifiedTime(path);
                    if (latest == null || time.compareTo(latestTime) > 0) {
                        latest = path;
                        latestTime = time;
                    }
                } catch (IOException e) {
                    throw new PluginException("会话文件时间读取失败: " + path, e);
                }
            }
        }
        return latest == null ? null : load(latest);
    }

    /** 会话 id（即 JSONL 文件名去后缀）。 */
    public String id() {
        return id;
    }

    /** JSONL 文件路径。 */
    public Path jsonl() {
        return jsonl;
    }

    /**
     * 事件日志的只读快照：调用时刻的稳定不可变视图——与 {@link #append} 的隔离
     * 经共享快照（CoW）实现：追加只在锁内整体重建快照并以 volatile 发布，读侧
     * 返回的引用要么是旧快照要么是新快照，绝无半态；遍历期间的追加落在另一份
     * 快照上，活视图会读到的扩容空洞与 CME 在此模型下不存在（Web SSE 回放与
     * agent 流式追加并发是常态）。无追加期间多次调用共享同一实例（读侧零拷贝）。
     */
    public List<SessionEvent> events() {
        return snapshot;
    }

    /**
     * 订阅事件：append 成功后同步回调，携带事件的日志序号（在 append-only 列表中的下标）——
     * 序号是重连游标（SSE 的 Last-Event-ID，ADR-0010）等消费方的锚点，由会话在写入处
     * 直接给出，消费方无须从日志末尾反推（反推在并发追加下会错位）。
     *
     * <p>回调在写线程上执行——实现应快速返回，重活自行转线程。</p>
     *
     * @param listener 事件回调（日志序号, 事件）
     * @return 注销器（幂等移除）
     */
    public Disposable addListener(BiConsumer<Integer, SessionEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * 唯一写入原语：内存追加 + JSONL 同步追加落盘（崩溃最多丢正在写的一条）。
     *
     * @throws IllegalStateException 会话已关闭（close 后写入属调用方错误——锁已释放，
     *                               继续写会与可能接手的新属主形成无锁并发）
     */
    public void append(SessionEvent event) {
        if (closed.get()) {
            throw new IllegalStateException("会话已关闭，不能再写入: " + id);
        }
        int index = persist(event);
        // 落盘成功后才通知——监听器看到的事件必然已持久化；序号在追加处固定（不重算）
        for (BiConsumer<Integer, SessionEvent> listener : listeners) {
            listener.accept(index, event);
        }
    }

    /**
     * 落盘 + 内存追加：**经持锁通道写**——不另开 fd。POSIX 语义下进程关闭同一文件
     * 的任意 fd 会释放其全部锁，若每次 append 走自己的 BufferedWriter，第一条事件
     * 写完独占锁就被自己放掉（跨进程防护蒸发）。序号在同步块内与追加一起确定。
     */
    private int persist(SessionEvent event) {
        synchronized (events) {
            try {
                byte[] bytes = (toJsonLine(event) + "\n").getBytes(StandardCharsets.UTF_8);
                lockChannel.position(lockChannel.size()); // 单写者（锁）保证末尾即追加点
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    lockChannel.write(buf);
                }
                lockChannel.force(false); // 事件溯源的持久化承诺：落盘后才返回
                events.add(event);
                snapshot = List.copyOf(events); // CoW 重建在锁内：读侧只见完整旧/新快照，绝无半态
                return events.size() - 1;
            } catch (IOException e) {
                throw new PluginException("会话事件落盘失败: " + event.type(), e);
            }
        }
    }

    /** 读满通道剩余字节（position → EOF）为字符串。 */
    private static String readAll(FileChannel channel) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(8_192);
        while (channel.read(buf) != -1) {
            buf.flip();
            out.write(buf.array(), buf.position(), buf.remaining());
            buf.clear();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * 投影：事件日志 → 对话消息列表（含 Function Calling 形态）。
     * 旧格式工具事件（无 toolCallId，协议关联缺失）跳过——不投影也不崩溃。
     * 遍历 {@link #events()} 快照而非活跃列表：读侧投影（含 Web 线程的状态面
     * 轮询）与追加线程并发隔离，不抛 ConcurrentModificationException。
     */
    public List<Message> deriveMessages() {
        List<Message> messages = new ArrayList<>();
        for (SessionEvent event : events()) {
            if (!projectsToMessage(event)) {
                continue;
            }
            switch (event.type()) {
                case SessionEvent.USER_MESSAGE ->
                        messages.add(new Message(Message.Role.USER, event.text()));
                case SessionEvent.ASSISTANT_MESSAGE ->
                        messages.add(new Message(Message.Role.ASSISTANT, event.text()));
                case SessionEvent.TOOL_CALL ->
                        messages.add(Message.assistantWithToolCalls(List.of(new ToolCall(
                                event.toolCallId(), event.toolName(), event.text())), event.reasoning()));
                case SessionEvent.TOOL_RESULT ->
                        messages.add(Message.tool(event.toolCallId(), event.text()));
                default -> { /* 不可达：projectsToMessage 已收窄类型集 */ }
            }
        }
        return messages;
    }

    /** 投影判定：该事件是否入对话消息列表（与 {@link #deriveMessages} 同一语义，尾部窗口映射复用）。 */
    private static boolean projectsToMessage(SessionEvent event) {
        return switch (event.type()) {
            case SessionEvent.USER_MESSAGE, SessionEvent.ASSISTANT_MESSAGE -> true;
            case SessionEvent.TOOL_CALL, SessionEvent.TOOL_RESULT -> event.toolCallId() != null;
            default -> false;
        };
    }

    /**
     * 尾部窗口映射（ADR-0013）：最后 {@code maxMessages} 条投影消息的事件区间起点。
     * 起点收在消息边界上——首屏不含残缺消息；tool/result 例外回折：结果卡的呈现依赖
     * 同 id 调用卡在场，调用落在窗外则结果成无源之果，故回折把调用一并纳入（窗口因此
     * 可比 maxMessages 多一条）。
     *
     * @param maxMessages 窗口内投影消息上限（须为正）
     * @return startEvent=窗口首事件的日志下标（未截断时为 0——全量窗口不裁前导非投影事件，
     *         如悬空审批卡），earlierMessages=起点之前的投影消息数（分页"更早还有 N 条"的计数源）
     * @throws IllegalArgumentException maxMessages 非正
     */
    public TailWindow tailWindow(int maxMessages) {
        List<SessionEvent> snapshot = events();
        return messageWindow(snapshot, snapshot.size(), maxMessages);
    }

    /**
     * 分页窗口（ADR-0013 / 工单 M13-02）：事件区间 {@code [0, endExclusive)} 内最后
     * {@code maxMessages} 条投影消息的事件区间起点与更早计数——与 {@link #tailWindow}
     * 同一套边界语义（消息边界对齐、tool/result 回折、未截断保留前导非投影事件），
     * 仅右边界参数化：分页以"当前窗口首事件序号"为右边界向前逐页取窗。
     *
     * @param endExclusive 右边界（事件下标上界，可取事件数；越界属调用方错误）
     * @param maxMessages  窗口内投影消息上限（须为正）
     * @return 语义同 {@link #tailWindow}
     * @throws IllegalArgumentException maxMessages 非正，或 endExclusive 越出 [0, 事件数]
     */
    public TailWindow windowBefore(int endExclusive, int maxMessages) {
        List<SessionEvent> snapshot = events();
        if (endExclusive < 0 || endExclusive > snapshot.size()) {
            throw new IllegalArgumentException("endExclusive 越出 [0, " + snapshot.size() + "]: " + endExclusive);
        }
        return messageWindow(snapshot, endExclusive, maxMessages);
    }

    /**
     * 消息锚定窗口的统一计算：区间 [0, endExclusive) 内最后 {@code maxMessages} 条
     * 投影消息的首事件下标（tool/result 回折同 id 调用）与之前的投影消息数。
     * 未截断（区间内消息不超上限）时起点固定 0——全量窗口不裁前导非投影事件
     * （悬空审批卡等是刷新后重建卡片的数据源）。单趟 O(n) 遍历：O(max) 下标环形
     * 缓冲记最近 maxMessages 条投影消息的位置，起点即缓冲中第 tailStart 号；
     * earlier 以 tailStart 为基线（起点之前恰有 tailStart 条投影消息，无需另趟
     * 统计），唯 tool/result 回折前移起点时按纳入窗口的消息数扣减（ADR-0014）。
     */
    private static TailWindow messageWindow(List<SessionEvent> snapshot, int endExclusive, int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages 必须为正: " + maxMessages);
        }
        int[] ring = new int[maxMessages]; // 最近 maxMessages 条投影消息的下标（第 k 号在 k % max 槽）
        int count = 0;
        for (int i = 0; i < endExclusive; i++) {
            if (projectsToMessage(snapshot.get(i))) {
                ring[count % maxMessages] = i;
                count++;
            }
        }
        int tailStart = Math.max(0, count - maxMessages);
        if (tailStart == 0) {
            return new TailWindow(0, 0);
        }
        // 截断时第 tailStart 号投影消息之后至多再写入 maxMessages-1 次，其槽位必未被覆盖
        int start = ring[tailStart % maxMessages];
        SessionEvent first = snapshot.get(start);
        if (SessionEvent.TOOL_RESULT.equals(first.type())) {
            // tool/result 回折：把同 id 调用卡纳入窗口。earlier 基线是 tailStart（起点前
            // 的投影消息数），回折使起点前移——起点与调用卡之间的每条投影消息转入窗口，
            // 按实际前移量从基线扣减；未找到同 id 调用（孤儿结果）则起点不动、基线不变。
            int fold = -1;
            int foldedIn = 0;
            for (int j = start - 1; j >= 0; j--) {
                SessionEvent prior = snapshot.get(j);
                if (projectsToMessage(prior)) {
                    foldedIn++;
                }
                if (SessionEvent.TOOL_CALL.equals(prior.type())
                        && prior.toolCallId() != null && prior.toolCallId().equals(first.toolCallId())) {
                    fold = j;
                    break;
                }
            }
            if (fold >= 0) {
                return new TailWindow(fold, tailStart - foldedIn);
            }
        }
        return new TailWindow(start, tailStart);
    }

    /** 尾部窗口映射结果（ADR-0013）：事件起点 + 起点之前的投影消息数。 */
    public record TailWindow(int startEvent, int earlierMessages) { }

    /**
     * 会话标题（latest-wins）：最新 {@code session/title} 事件的文本；无标题事件
     * 返回 null（调用方回退 id 呈现）。标题生成器（工单 M13-06）一次写入，重写由
     * latest-wins 自然覆盖——当前产品形态不重生成、不可改名。
     */
    public String title() {
        List<SessionEvent> snapshot = events();
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            if (SessionEvent.TITLE.equals(snapshot.get(i).type())) {
                return snapshot.get(i).text();
            }
        }
        return null;
    }

    /**
     * 静态标题读取（侧栏列表用）：不持锁打开 JSONL 逐行找最新 title 事件——
     * 与 load 的严格解析不同，损坏行跳过不抛（标注是锦上添花，不因脏行失败）。
     * 文件缺失/不可读返回 null。
     */
    public static String titleOf(Path jsonl) {
        if (!Files.isRegularFile(jsonl)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(jsonl);
            for (int i = lines.size() - 1; i >= 0; i--) {
                try {
                    JsonNode node = JSON.readTree(lines.get(i));
                    if (SessionEvent.TITLE.equals(node.path("type").asText())) {
                        return node.path("text").asText();
                    }
                } catch (IOException ignored) {
                    // 单行损坏跳过（探测语义宽松）
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 新会话 id：启动时间 + 4 位十六进制随机后缀（补零保证同秒内字典序与生成序一致）。 */
    private static String newId() {
        String suffix = String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));
        return LocalDateTime.now().format(ID_TIMESTAMP) + "-" + suffix;
    }

    /** 事件序列化为 JSONL 行（Jackson 统一读写路径；可选字段按存在写入——assistant/message 另带 usage）。 */
    private static String toJsonLine(SessionEvent event) throws IOException {
        var node = JSON.createObjectNode();
        node.put("type", event.type());
        node.put("at", event.at());
        node.put("text", event.text());
        if (event.toolCallId() != null) {
            node.put("toolCallId", event.toolCallId());
        }
        if (event.toolName() != null) {
            node.put("toolName", event.toolName());
        }
        if (event.reasoning() != null) {
            node.put("reasoning", event.reasoning());
        }
        if (event.usage() != null) {
            node.putObject("usage")
                    .put("promptTokens", event.usage().promptTokens())
                    .put("completionTokens", event.usage().completionTokens())
                    .put("totalTokens", event.usage().totalTokens());
        }
        return JSON.writeValueAsString(node);
    }

    private static SessionEvent parse(String line) {
        try {
            JsonNode node = JSON.readTree(line);
            String type = node.path("type").asText();
            long at = node.path("at").asLong();
            String text = node.path("text").asText();
            JsonNode idNode = node.get("toolCallId");
            JsonNode nameNode = node.get("toolName");
            JsonNode reasoningNode = node.get("reasoning");
            JsonNode usageNode = node.get("usage");
            TokenUsage usage = usageNode == null || !usageNode.isObject() ? null : new TokenUsage(
                    usageNode.path("promptTokens").asLong(0),
                    usageNode.path("completionTokens").asLong(0),
                    usageNode.path("totalTokens").asLong(0));
            return new SessionEvent(type, at, text,
                    idNode == null || idNode.isNull() ? null : idNode.asText(),
                    nameNode == null || nameNode.isNull() ? null : nameNode.asText(),
                    reasoningNode == null || reasoningNode.isNull() ? null : reasoningNode.asText(),
                    usage);
        } catch (IOException e) {
            throw new PluginException("会话事件解析失败: " + line, e);
        }
    }

}
