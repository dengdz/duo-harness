package dev.duo.harness.sessionquery;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * session_search 工具（M21，ADR-0022 决策 8）：模型侧会话历史检索——单 query
 * 分词 AND，返回命中会话（标题/更新时间）+ 最强匹配事件（类型/摘录）。
 * 纯本地读 harness 自身数据目录（会话日志），不经 workspace 档位——不声明审批，
 * 与附件库写同判（harness 内部数据不受三档管辖，ADR-0022 决策 10）。
 */
public final class SessionSearchTool implements ToolDefinition {

    public static final String NAME = "session_search";

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final SessionQueryService service;
    private final int maxResults;

    public SessionSearchTool(SessionQueryService service, int maxResults) {
        this.service = service;
        this.maxResults = maxResults;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "按关键词搜索历史会话，返回命中的会话（标题与更新时间）及最强匹配的事件摘录。"
                + "用于回答“上次讨论过 X 吗”这类对历史内容的查找；引用结果时注明会话 id。";
    }

    @Override public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    "{\"type\":\"object\",\"properties\":{"
                            + "\"query\":{\"type\":\"string\",\"description\":\"搜索关键词（可中英混排，多词为与关系）\"}"
                            + "},\"required\":[\"query\"]}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 检索服务内部全程持锁，搜索无共享可变状态外露——并发安全（ADR-0018）。 */
    @Override public boolean isConcurrencySafe(JsonNode args) { return true; }

    @Override public String execute(ToolExecution exec) {
        String query = exec.args().path("query").asText("").strip();
        if (query.isEmpty()) {
            return "[session_search 错误] 参数 query 不能为空";
        }
        List<SessionHit> hits = service.search(query, maxResults);
        if (hits.isEmpty()) {
            return "No results found.";
        }
        StringBuilder out = new StringBuilder("Session search: ").append(query)
                .append("\n\n共 ").append(hits.size()).append(" 个会话命中:");
        for (SessionHit hit : hits) {
            out.append("\n- 会话 ").append(hit.sessionId())
                    .append(hit.title() == null ? "" : " 「" + hit.title() + "」")
                    .append("（更新于 ").append(TIME.format(Instant.ofEpochMilli(hit.lastModifiedMs())))
                    .append("）");
            out.append("\n  ").append(hit.eventType()).append(" #").append(hit.eventIndex())
                    .append(": ").append(hit.snippet());
        }
        return out.toString();
    }
}
