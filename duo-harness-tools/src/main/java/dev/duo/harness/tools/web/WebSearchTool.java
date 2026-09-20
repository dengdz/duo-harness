package dev.duo.harness.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.net.URI;
import java.util.List;

/**
 * web_search 工具（M20，ADR-0021 决策 2/3/6）：单关键词全网搜索，返回
 * "不可信声明 + Sources 列表 + 引用指引"。参数面最小化——仅 query；多路搜索由
 * 模型并行多调用（本工具并发安全，duo 并发池原生接住），不做 DSH 的 queries[]
 * 数组合并。空结果明示 "No results found."。
 */
public final class WebSearchTool implements ToolDefinition {

    public static final String NAME = "web_search";

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final TavilyProvider provider;
    private final int maxResults;
    /** read-only 档探测（插件按 workspace 档位供给；null = 无档位装配，不声明审批）。 */
    private final java.util.function.BooleanSupplier readOnlyGate;

    WebSearchTool(TavilyProvider provider, int maxResults, java.util.function.BooleanSupplier readOnlyGate) {
        this.provider = provider;
        this.maxResults = maxResults;
        this.readOnlyGate = readOnlyGate;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "用关键词搜索全网，返回标题、链接与摘要列表。用于查找资料来源、核实信息；"
                + "对感兴趣的条目再用 web_fetch 抓取全文。";
    }

    @Override public JsonNode parameters() {
        try {
            return MAPPER.readTree(
                    "{\"type\":\"object\",\"properties\":{"
                            + "\"query\":{\"type\":\"string\",\"description\":\"搜索关键词\"}"
                            + "},\"required\":[\"query\"]}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 纯只读、无共享可变状态——多路搜索进并行池（ADR-0018）。 */
    @Override public boolean isConcurrencySafe(JsonNode args) { return true; }

    /** 网络读档位声明（同 web_fetch，ADR-0021 决策 8）：read-only 档声明、其余档不声明。 */
    @Override
    public boolean requiresApproval() {
        return readOnlyGate != null && readOnlyGate.getAsBoolean();
    }

    @Override public String execute(ToolExecution exec) {
        String query = exec.args().path("query").asText("").strip();
        if (query.isEmpty()) {
            return "[web_search 错误] 参数 query 不能为空";
        }
        List<SearchSource> sources = dedupeByUrl(provider.search(query, maxResults));
        if (sources.size() > maxResults) {
            sources = sources.subList(0, maxResults); // 工具层兜底截断（截断权不依赖 provider 自律）
        }
        if (sources.isEmpty()) {
            return "No results found.";
        }
        StringBuilder out = new StringBuilder("Web search: ").append(query).append("\n\n")
                .append(WebFetchTool.UNTRUSTED_NOTICE).append("\n\nSources:");
        for (SearchSource source : sources) {
            out.append("\n- [").append(source.title().isBlank() ? source.url() : source.title())
                    .append("](").append(source.url()).append(')');
            if (!source.snippet().isBlank()) {
                out.append(" — ").append(source.snippet());
            }
        }
        out.append("\n\n回答时请以 Markdown 链接形式引用上列相关 URL。");
        return out.toString();
    }

    /**
     * URL 归一化去重：同一站点以 http/https、带不带 www 的变体重复出现时只保留
     * 首条——host（去 www 前缀，小写）+ 路径 + 查询串相同即视为重复。
     */
    private static List<SearchSource> dedupeByUrl(List<SearchSource> sources) {
        java.util.LinkedHashMap<String, SearchSource> unique = new java.util.LinkedHashMap<>();
        for (SearchSource source : sources) {
            unique.putIfAbsent(normalizeKey(source.url()), source);
        }
        return List.copyOf(unique.values());
    }

    private static String normalizeKey(String url) {
        try {
            URI uri = URI.create(url.strip());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return host + path + query;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
