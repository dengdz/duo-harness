package dev.duo.harness.attachment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Files API 客户端（M21 工单 06，ADR-0022 决策 5）：上传图片换 file_id，
 * 省大图 base64 传输。仅 DeepSeek 形态端点（POST {base}/files multipart，
 * purpose=user_data）。上传失败由调用方回退 inline base64。
 *
 * <p>无本地索引去重与配额回收——一期精简，后续按需增补（limitations 记账）。</p>
 */
public class FilesApiUploader {

    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient client;

    public FilesApiUploader(String baseUrl, String apiKey, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
    }

    /** 上传图片，返回 file_id；非 2xx 或网络异常抛 {@link FilesApiException}。 */
    public String upload(byte[] imageBytes, String mediaType, String fileName) {
        String boundary = "----duo" + java.util.UUID.randomUUID().toString().replace("-", "");
        var body = new java.io.ByteArrayOutputStream();
        try {
            body.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                    + "Content-Type: " + mediaType + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            body.write(imageBytes);
            body.write(("\r\n--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"purpose\"\r\n\r\n"
                    + "user_data\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("multipart 构建失败", e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/files"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new FilesApiException("Files API 超时", e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new FilesApiException("Files API 请求失败: " + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new FilesApiException("Files API 返回 HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        // 解析 file_id
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
            String fileId = root.path("id").asText("");
            if (fileId.isBlank()) throw new FilesApiException("Files API 响应缺少 id");
            return fileId;
        } catch (IOException e) {
            throw new FilesApiException("Files API 响应解析失败", e);
        }
    }

    /** Files API 业务异常（调用方转为 inline 回退）。 */
    public static class FilesApiException extends RuntimeException {
        public FilesApiException(String message) { super(message); }
        public FilesApiException(String message, Throwable cause) { super(message, cause); }
    }
}
