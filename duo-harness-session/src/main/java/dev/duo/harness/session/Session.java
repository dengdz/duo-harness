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
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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
 * （M8 起 Web SSE 回放与 agent 流式追加并发是常态）。</p>
 */
public final class Session {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ID_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final String id;
    private final Path jsonl;
    private final List<SessionEvent> events = new ArrayList<>();
    /** 事件监听器（CoW：回调中注销不破坏遍历）。 */
    private final List<Consumer<SessionEvent>> listeners = new CopyOnWriteArrayList<>();

    private Session(String id, Path jsonl) {
        this.id = id;
        this.jsonl = jsonl;
    }

    /** 新建会话：生成 id 并创建 JSONL 文件。 */
    public static Session create(Path sessionsDir) {
        String id = newId();
        Path file = sessionsDir.resolve(id + ".jsonl");
        try {
            Files.createDirectories(sessionsDir);
            Files.createFile(file);
        } catch (IOException e) {
            throw new PluginException("无法创建会话文件: " + file, e);
        }
        return new Session(id, file);
    }

    /** 从 JSONL 重放读回会话（文件必须存在且为合法会话日志）。 */
    public static Session load(Path jsonl) {
        String id = jsonl.getFileName().toString();
        if (id.endsWith(".jsonl")) {
            id = id.substring(0, id.length() - ".jsonl".length());
        }
        Session session = new Session(id, jsonl);
        try (BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                session.events.add(parse(line));
            }
        } catch (IOException e) {
            throw new PluginException("会话文件读取失败: " + jsonl, e);
        }
        return session;
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
     * 事件日志的只读快照：调用时刻的稳定拷贝——流式追加期间遍历安全
     * （Web SSE 回放与 agent 追加并发是常态，活视图会在遍历中抛 CME）。
     */
    public List<SessionEvent> events() {
        return List.copyOf(events);
    }

    /**
     * 订阅事件：append 成功后同步回调（M8 事件流的推送源，如 Web SSE）。
     *
     * <p>回调在写线程上执行——实现应快速返回，重活自行转线程。</p>
     *
     * @param listener 事件回调
     * @return 注销器（幂等移除）
     */
    public Disposable addListener(Consumer<SessionEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** 唯一写入原语：内存追加 + JSONL 同步追加落盘（崩溃最多丢正在写的一条）。 */
    public void append(SessionEvent event) {
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
            events.add(event);
        } catch (IOException e) {
            throw new PluginException("会话事件落盘失败: " + event.type(), e);
        }
        // 落盘成功后才通知——监听器看到的事件必然已持久化
        for (Consumer<SessionEvent> listener : listeners) {
            listener.accept(event);
        }
    }

    /**
     * 投影：事件日志 → 对话消息列表（含 Function Calling 形态）。
     * 旧格式工具事件（无 toolCallId，协议关联缺失）跳过——不投影也不崩溃。
     */
    public List<Message> deriveMessages() {
        List<Message> messages = new ArrayList<>();
        for (SessionEvent event : events) {
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

    /** 事件序列化为 JSONL 行（Jackson 统一读写路径；工具事件额外携带 toolCallId/toolName，tool/call 另带 reasoning）。 */
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
            return new SessionEvent(type, at, text,
                    idNode == null || idNode.isNull() ? null : idNode.asText(),
                    nameNode == null || nameNode.isNull() ? null : nameNode.asText(),
                    reasoningNode == null || reasoningNode.isNull() ? null : reasoningNode.asText());
        } catch (IOException e) {
            throw new PluginException("会话事件解析失败: " + line, e);
        }
    }

}
