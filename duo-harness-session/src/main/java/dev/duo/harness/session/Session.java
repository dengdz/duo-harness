package dev.duo.harness.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Collections;
import java.util.List;
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
 * <p>线程约定：实例非线程安全——单会话内串行使用（REPL/agent 循环均为串行消费）。</p>
 */
public final class Session {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ID_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final String id;
    private final Path jsonl;
    private final List<SessionEvent> events = new ArrayList<>();

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

    /** 事件日志的只读视图。 */
    public List<SessionEvent> events() {
        return Collections.unmodifiableList(events);
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
    }

    /** 投影：事件日志 → 对话消息列表（user/message 与 assistant/message 入列，chunk 不投影）。 */
    public List<Message> deriveMessages() {
        List<Message> messages = new ArrayList<>();
        for (SessionEvent event : events) {
            switch (event.type()) {
                case SessionEvent.USER_MESSAGE ->
                        messages.add(new Message(Message.Role.USER, event.text()));
                case SessionEvent.ASSISTANT_MESSAGE ->
                        messages.add(new Message(Message.Role.ASSISTANT, event.text()));
                case SessionEvent.TOOL_RESULT ->
                        messages.add(new Message(Message.Role.TOOL, event.text()));
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

    /** 事件序列化为 JSONL 行（Jackson 统一读写路径，转义与格式由 ObjectMapper 保证）。 */
    private static String toJsonLine(SessionEvent event) throws IOException {
        return JSON.writeValueAsString(java.util.Map.of(
                "type", event.type(), "at", event.at(), "text", event.text()));
    }

    private static SessionEvent parse(String line) {
        try {
            JsonNode node = JSON.readTree(line);
            String type = node.path("type").asText();
            long at = node.path("at").asLong();
            String text = node.path("text").asText();
            return new SessionEvent(type, at, text);
        } catch (IOException e) {
            throw new PluginException("会话事件解析失败: " + line, e);
        }
    }

}
