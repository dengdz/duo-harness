package dev.duo.harness.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
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
    /** 事件监听器（CoW：回调中注销不破坏遍历）。 */
    private final List<BiConsumer<Integer, SessionEvent>> listeners = new CopyOnWriteArrayList<>();
    /** 独占锁的文件通道（持有至 {@link #close()}）。 */
    private final FileChannel lockChannel;
    /** 会话文件的独占锁（进程级单写者检测）。 */
    private final FileLock fileLock;
    /** 已关闭标志（close 幂等）。 */
    private final AtomicBoolean closed =
            new AtomicBoolean(false);

    private Session(String id, Path jsonl, FileChannel lockChannel,
                    FileLock fileLock) {
        this.id = id;
        this.jsonl = jsonl;
        this.lockChannel = lockChannel;
        this.fileLock = fileLock;
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
        try (BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                session.events.add(parse(line));
            }
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
    private static Session lock(String id, Path jsonl) {
        FileChannel channel = null;
        try {
            channel = FileChannel.open(jsonl,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock fileLock = channel.tryLock();
            if (fileLock == null) {
                channel.close();
                throw new SessionLockedException(id, jsonl);
            }
            return new Session(id, jsonl, channel, fileLock);
        } catch (OverlappingFileLockException e) {
            closeQuietly(channel);
            throw new SessionLockedException(id, jsonl, e);
        } catch (IOException e) {
            closeQuietly(channel);
            throw new PluginException("会话文件打开失败: " + jsonl, e);
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
     * 关闭会话：释放独占锁与文件通道、摘除全部事件监听器（幂等）。本进程不再独占该会话，
     * 其他进程与实例可重新打开；调用方应在会话生命周期结束时调用（Web 停止、CLI 退出、
     * 换绑到其他会话时）。关闭后写入与订阅均失效——应停止使用本实例。
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
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
     * 事件日志的只读快照：调用时刻的稳定拷贝——与 {@link #append} 经同一把锁互斥，
     * 追加期间的拷贝与遍历都安全（裸 ArrayList 并发拷贝会读到扩容空洞而 NPE，
     * 活视图遍历会抛 CME；Web SSE 回放与 agent 流式追加并发是常态）。
     */
    public List<SessionEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
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

    /** 落盘 + 内存追加：序号在同步块内与追加一起确定（并发快照读不到半写状态）。 */
    private int persist(SessionEvent event) {
        try {
            boolean freshFile = !Files.exists(jsonl);
            if (freshFile) {
                Files.createDirectories(jsonl.getParent());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(jsonl, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(toJsonLine(event));
                writer.newLine();
            }
            synchronized (events) {
                events.add(event);
                return events.size() - 1;
            }
        } catch (IOException e) {
            throw new PluginException("会话事件落盘失败: " + event.type(), e);
        }
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
            switch (event.type()) {
                case SessionEvent.USER_MESSAGE ->
                        messages.add(new Message(Message.Role.USER, event.text()));
                case SessionEvent.ASSISTANT_MESSAGE ->
                        messages.add(new Message(Message.Role.ASSISTANT, event.text()));
                case SessionEvent.TOOL_CALL -> {
                    if (event.toolCallId() == null) {
                        break;
                    }
                    messages.add(Message.assistantWithToolCalls(List.of(new ToolCall(
                            event.toolCallId(), event.toolName(), event.text())), event.reasoning()));
                }
                case SessionEvent.TOOL_RESULT -> {
                    if (event.toolCallId() == null) {
                        break;
                    }
                    messages.add(Message.tool(event.toolCallId(), event.text()));
                }
                default -> { /* 流式 chunk 与未知类型不投影 */ }
            }
        }
        return messages;
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
