package dev.duo.harness.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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
    private final long maxResponseBytes;
    private final HttpClient client;

    TavilyProvider(String apiKey, String baseUrl, int timeoutMs, long maxResponseBytes) {
        this.apiKey = apiKey;
        // 归一化尾斜杠：配置错误在构造期消除 "…//search" 变体，而非每次 search 运行期报晦涩 404
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeoutMs = timeoutMs;
        this.maxResponseBytes = maxResponseBytes;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 10_000L)))
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
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new RuntimeException("[web_search 错误] 超时（" + timeoutMs + "ms）——搜索服务无响应", e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 不链 cause：rootMessage 取最深 cause，链上会顶掉带前缀的指引文本
            throw new RuntimeException("[web_search 错误] 搜索服务请求失败: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (contentLength > maxResponseBytes) {
            try {
                response.body().close();
            } catch (IOException ignored) {
                // 放弃响应后的关流失败无可补救：超限错误照常抛出
            }
            throw new RuntimeException("[web_search 错误] 搜索服务响应过大（Content-Length " + contentLength
                    + " 超上限 " + maxResponseBytes + "）");
        }
        BodyReader.ReadResult read;
        try {
            read = BodyReader.read(response.body(), maxResponseBytes, Duration.ofMillis(timeoutMs));
        } catch (BodyReader.FetchTimeoutException e) {
            // 不链 cause：保住点名时长的指引文本（管线 rootMessage 取最深 cause）
            throw new RuntimeException("[web_search 错误] 超时（" + timeoutMs + "ms）——搜索服务响应过慢");
        } catch (IOException e) {
            throw new RuntimeException("[web_search 错误] 读取搜索服务响应失败: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (read.truncated()) {
            throw new RuntimeException("[web_search 错误] 搜索服务响应超过 " + maxResponseBytes
                    + " 字节上限且未声明 Content-Length——已放弃解析");
        }
        String bodyJson = new String(read.data(), java.nio.charset.StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("[web_search 错误] 搜索服务返回 HTTP " + response.statusCode()
                    + ": " + excerpt(bodyJson));
        }
        return normalize(bodyJson);
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
