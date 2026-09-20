package dev.duo.harness.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Tavily 搜索 provider（M20，ADR-0021 决策 3）：首发搜索后端——纯 REST
 * （POST {base}/search，Bearer 鉴权），返回归一化 sources。
 *
 * <p>provider 接口按"一次调用返回归一化结果"设计（无流式、无 answer 字段），
 * 后续 Brave / Perplexity / DeepSeek 为增量实现（同一 {@link SearchSource} 契约）。
 * 限流（429）无特判——错误原文照给模型（配额语义对模型可见反而有用）。</p>
 */
final class TavilyProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String baseUrl;
    private final int timeoutMs;
    private final HttpClient client;

    TavilyProvider(String apiKey, String baseUrl, int timeoutMs) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.timeoutMs = timeoutMs;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 搜索单 query，返回至多 maxResults 条归一化结果。 */
    List<SearchSource> search(String query, int maxResults) {
        String requestJson = MAPPER.createObjectNode()
                .put("query", query)
                .put("max_results", maxResults)
                .toString();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/search"))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new RuntimeException("[web_search 错误] 超时（" + timeoutMs + "ms）——搜索服务无响应", e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("[web_search 错误] 搜索服务请求失败: " + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("[web_search 错误] 搜索服务返回 HTTP " + response.statusCode()
                    + ": " + excerpt(response.body()));
        }
        return normalize(response.body());
    }

    /** 归一化：results[] → sources（title/url/content）；缺字段按空串处理。 */
    private List<SearchSource> normalize(String bodyJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(bodyJson);
        } catch (IOException e) {
            throw new RuntimeException("[web_search 错误] 搜索服务响应非 JSON: " + excerpt(bodyJson), e);
        }
        List<SearchSource> sources = new ArrayList<>();
        JsonNode results = root.path("results");
        for (JsonNode result : results) {
            String url = result.path("url").asText("");
            if (url.isBlank()) {
                continue;
            }
            sources.add(new SearchSource(url, result.path("title").asText(""), result.path("content").asText("")));
        }
        return sources;
    }

    private static String excerpt(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.length() <= 200 ? stripped : stripped.substring(0, 200) + "…";
    }
}
