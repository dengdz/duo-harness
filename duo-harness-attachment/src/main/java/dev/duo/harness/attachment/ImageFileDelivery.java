package dev.duo.harness.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片投递服务（M21 工单 06，ADR-0022 决策 5）：{@code imageDelivery: files} 时
 * 把请求变体字节上传 Files API 换 file_id，本地索引去重（同 variantId 命中不重传），
 * 配额类失败回收最旧自有文件后重试一次。上传失败整体上抛——调用方回退 inline
 * base64（通用兜底）。
 *
 * <p>本地索引落 {@code cache/attachments/files-index.json}（原子写：tmp + move），
 * 进程重启后去重仍有效；索引即"自有文件台账"——回收只删台账内的文件。</p>
 *
 * <p>线程约定：synchronized 串行（上传低频、字节已在内存，无并发必要）。</p>
 */
public class ImageFileDelivery {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FilesApiUploader uploader;
    private final Path indexFile;
    /** 本地索引：variantId → 台账条目（LinkedHashMap 保插入序 = 上传序，回收取最旧）。 */
    private final Map<String, Entry> index = new LinkedHashMap<>();

    /**
     * @param uploader  Files API 客户端（base 指向 provider 根）
     * @param indexFile 索引持久化路径（如 cache/attachments/files-index.json；父目录按需创建）
     */
    public ImageFileDelivery(FilesApiUploader uploader, Path indexFile) {
        this.uploader = uploader;
        this.indexFile = indexFile;
        loadIndex();
    }

    /** 索引台账条目。 */
    public record Entry(String fileId, long uploadedAtMs, String mediaType) { }

    /**
     * 投递一个请求变体：命中索引直接返回 file_id；未命中上传；配额类失败回收最旧
     * 自有文件后重试一次；仍失败上抛 {@link FilesApiUploader.FilesApiException}
     * （调用方回退 inline）。
     *
     * @param variantId 请求变体 id（去重键；同一变体字节恒定，file_id 可复用）
     * @param bytes     变体图片字节
     * @param mediaType 图片媒体类型
     * @param fileName  上传文件名（provider 侧展示用，可空）
     * @return file_id
     */
    public synchronized String deliver(String variantId, byte[] bytes,
                                       String mediaType, String fileName) {
        Entry entry = index.get(variantId);
        if (entry != null) {
            return entry.fileId(); // 去重命中：不重传
        }
        try {
            return uploadAndRecord(variantId, bytes, mediaType, fileName);
        } catch (FilesApiUploader.FilesApiException quotaFailure) {
            if (!uploader.looksLikeQuotaFailure(quotaFailure) || index.isEmpty()) {
                throw quotaFailure;
            }
            evictOldest(); // 配额满：回收最旧自有文件后重试一次
            return uploadAndRecord(variantId, bytes, mediaType, fileName);
        }
    }

    /** 失效一个 file_id（provider 侧已不可用时清台账——下次投递自然重传）。 */
    public synchronized void invalidate(String fileId) {
        index.values().removeIf(entry -> entry.fileId().equals(fileId));
        persistIndex();
    }

    /** 台账规模（测试与诊断）。 */
    public synchronized int size() {
        return index.size();
    }

    private String uploadAndRecord(String variantId, byte[] bytes,
                                   String mediaType, String fileName) {
        String name = fileName == null || fileName.isBlank()
                ? "image-" + variantId.substring(0, Math.min(12, variantId.length()))
                : fileName;
        String fileId = uploader.upload(bytes, mediaType, name);
        index.put(variantId, new Entry(fileId, System.currentTimeMillis(), mediaType));
        persistIndex();
        return fileId;
    }

    /** 回收最旧自有文件（台账删除 + provider 删除；provider 删除失败不回滚台账——下次回收再试）。 */
    private void evictOldest() {
        Entry oldest = index.values().stream()
                .min(Comparator.comparingLong(Entry::uploadedAtMs))
                .orElse(null);
        if (oldest == null) {
            return;
        }
        uploader.delete(oldest.fileId());
        index.values().removeIf(entry -> entry.fileId().equals(oldest.fileId()));
        persistIndex();
    }

    /** 索引加载（文件缺失/损坏 = 空台账起步——provider 侧孤儿文件等配额回收自然清理）。 */
    private void loadIndex() {
        if (!Files.isRegularFile(indexFile)) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(indexFile, StandardCharsets.UTF_8));
            root.properties().forEach(property -> {
                JsonNode value = property.getValue();
                index.put(property.getKey(), new Entry(
                        value.path("fileId").asText(),
                        value.path("uploadedAtMs").asLong(0),
                        value.path("mediaType").asText("")));
            });
        } catch (IOException | RuntimeException e) {
            index.clear(); // 损坏索引当空起步（重建台账，provider 侧孤儿靠配额回收兜底）
        }
    }

    /** 索引持久化（tmp + ATOMIC_MOVE，崩溃不留半截索引）。 */
    private void persistIndex() {
        try {
            ObjectNode root = JSON.createObjectNode();
            for (Map.Entry<String, Entry> entry : index.entrySet()) {
                root.putObject(entry.getKey())
                        .put("fileId", entry.getValue().fileId())
                        .put("uploadedAtMs", entry.getValue().uploadedAtMs())
                        .put("mediaType", entry.getValue().mediaType());
            }
            Files.createDirectories(indexFile.getParent());
            Path tmp = indexFile.resolveSibling(indexFile.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root), StandardCharsets.UTF_8);
            Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 索引持久化失败不阻断投递（内存台账仍有效；重启后可能重传一次）
        }
    }
}
