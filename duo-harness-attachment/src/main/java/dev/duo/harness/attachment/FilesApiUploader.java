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
 * <p>本类只做单次上传/删除的 HTTP 语义；去重、配额回收与失效清理由
 * {@link ImageFileDelivery} 编排。</p>
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
                    + ": " + response.body(), null, response.statusCode(), response.body());
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

    /** Files API 业务异常（调用方转为 inline 回退）；携带结构化状态码与响应体（C2 工单 14）。 */
    public static class FilesApiException extends RuntimeException {
        /** HTTP 状态码；0 = 无状态（网络/超时/解析类失败）。 */
        private final int statusCode;
        /** 响应体原文（判 provider 措辞用）；无响应体为 null。 */
        private final String responseBody;

        public FilesApiException(String message) {
            this(message, null, 0, null);
        }

        public FilesApiException(String message, Throwable cause) {
            this(message, cause, 0, null);
        }

        public FilesApiException(String message, Throwable cause, int statusCode, String responseBody) {
            super(message, cause);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public int statusCode() {
            return statusCode;
        }

        public String responseBody() {
            return responseBody;
        }
    }

    /**
     * 删除自有文件（配额回收用，M21 工单 06）：DELETE /files/{file_id}；
     * 404 视为已删除（幂等）。网络类异常上抛（回收失败交由调用方重试策略）。
     */
    public void delete(String fileId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/files/" + fileId))
                .DELETE()
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new FilesApiException("Files API 删除超时", e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new FilesApiException("Files API 删除失败: " + e.getMessage(), e);
        }
        int status = response.statusCode();
        if (status == 404) {
            return; // 已不存在 = 回收目标达成
        }
        if (status < 200 || status >= 300) {
            throw new FilesApiException("Files API 删除返回 HTTP " + status + ": " + response.body(),
                    null, status, response.body());
        }
    }

    /**
     * 配额类失败启发判定（C2 工单 14）：状态码走<b>结构化字段</b>（此前从异常消息
     * 文本捞 {@code "http 400"}——消息格式一变判定即静默失效、配额回收不触发）；
     * 响应体关键词（quota/limit/exceed/full）判 provider 间的措辞差异。
     */
    public boolean looksLikeQuotaFailure(FilesApiException e) {
        boolean statusShape = e.statusCode() == 400 || e.statusCode() == 403
                || e.statusCode() == 413 || e.statusCode() == 429;
        if (!statusShape) {
            return false;
        }
        String body = e.responseBody() == null
                ? "" : e.responseBody().toLowerCase(java.util.Locale.ROOT);
        return body.contains("quota") || body.contains("limit")
                || body.contains("exceed") || body.contains("full");
    }
}
