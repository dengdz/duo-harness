package dev.duo.harness.attachment;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

/**
 * 请求变体管线（M21 工单 02，ADR-0022 决策 5）：发给视觉模型前按目标确定性缩放 +
 * 字节预算压缩，变体缓存落盘、同变体并发单飞。
 *
 * <p>variantId = SHA-256(attachmentId + 目标参数 + 编码版本)——同附件同目标必得
 * 同变体字节（确定性）；缓存 {@code <cacheRoot>/<2位>/<variantId>}，命中即零计算。
 * 缓存是纯优化：损坏/缺失即重算重写，不构成正确性依赖。</p>
 *
 * <p>线程约定：在册单飞表按 variantId 收敛并发——首个到达者计算，余者等同一
 * future；失败即摘除映射，下一个调用者重试。</p>
 */
public class RequestVariants {

    /** 编码版本——编码语义变更时递增使旧缓存全体失效。 */
    static final String ENCODING_VERSION = "request-variant-v1";

    private final AttachmentStore store;
    private final Path cacheRoot;
    private final ConcurrentHashMap<String, CompletableFuture<RequestVariant>> inFlight = new ConcurrentHashMap<>();

    public RequestVariants(AttachmentStore store, Path cacheRoot) {
        this.store = store;
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
    }

    /** 按缺省目标取变体（一期统一目标，模型目录级目标留接口）。 */
    public RequestVariant variantFor(String attachmentId) {
        return variantFor(attachmentId, VariantTarget.DEFAULT);
    }

    /** 按指定目标取变体：缓存命中直读；未命中单飞计算（缩放 + 预算适配）后落缓存。 */
    public RequestVariant variantFor(String attachmentId, VariantTarget target) {
        String variantId = variantId(attachmentId, target);
        RequestVariant cached = readCache(variantId);
        if (cached != null) {
            return cached;
        }
        while (true) {
            CompletableFuture<RequestVariant> existing = inFlight.get(variantId);
            if (existing != null) {
                return await(existing);
            }
            CompletableFuture<RequestVariant> mine = new CompletableFuture<>();
            existing = inFlight.putIfAbsent(variantId, mine);
            if (existing != null) {
                return await(existing);
            }
            try {
                mine.complete(computeAndCache(attachmentId, target, variantId));
            } catch (RuntimeException e) {
                mine.completeExceptionally(e);
            } finally {
                inFlight.remove(variantId, mine); // 失败即摘除：下一个调用者重试，不让坏 future 占坑
            }
            return await(mine);
        }
    }

    private static RequestVariant await(CompletableFuture<RequestVariant> future) {
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    /** variantId 确定性：附件 + 目标 + 编码版本三要素的 SHA-256。 */
    static String variantId(String attachmentId, VariantTarget target) {
        return AttachmentStore.sha256Hex(
                (attachmentId + "|" + target.longEdge() + "|" + target.byteBudget()
                        + "|" + ENCODING_VERSION).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 缓存读取：损坏视为未命中（缓存是优化不是正确性依赖），尺寸经解码取真值。 */
    private RequestVariant readCache(String variantId) {
        Path file = cacheRoot.resolve(variantId.substring(0, 2)).resolve(variantId);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(file);
            String mediaType = ImageFormats.sniff(data).orElse(null);
            BufferedImage image = mediaType == null ? null : ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) {
                return null;
            }
            return new RequestVariant(variantId, mediaType, data, image.getWidth(), image.getHeight());
        } catch (IOException e) {
            return null;
        }
    }

    /** 计算 + 落缓存（子类可覆写注入测试钩子；生产路径为真实计算）。 */
    RequestVariant computeAndCache(String attachmentId, VariantTarget target, String variantId) {
        if (!store.exists(attachmentId)) {
            throw new AttachmentException("附件不存在: " + attachmentId);
        }
        byte[] source;
        try {
            source = Files.readAllBytes(store.objectPath(attachmentId));
        } catch (IOException e) {
            throw new AttachmentException("读取附件失败: " + e.getMessage());
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(source));
        } catch (IOException e) {
            image = null;
        }
        if (image == null) {
            throw new AttachmentException("附件解码失败: " + attachmentId);
        }
        RequestVariant variant = transform(image, target, variantId);
        writeCache(variantId, variant.bytes());
        return variant;
    }

    /** 只缩不放的长边缩放 + 字节预算适配（质量阶梯逐次降质，封顶后接受）。 */
    private RequestVariant transform(BufferedImage source, VariantTarget target, String variantId) {
        boolean alpha = source.getColorModel().hasAlpha();
        String format = alpha ? "png" : "jpeg";
        int longEdge = Math.max(source.getWidth(), source.getHeight());
        double scale = Math.min(1.0, (double) target.longEdge() / longEdge);
        try {
            for (int attempt = 0; attempt < 4; attempt++) {
                int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
                BufferedImage scaled = scale < 1.0
                        ? net.coobird.thumbnailator.Thumbnails.of(source).size(w, h).asBufferedImage()
                        : source;
                double quality = Math.max(0.4, 0.9 - attempt * 0.2);
                byte[] encoded = ImageNormalizer.encode(
                        alpha ? scaled : ImageNormalizer.flatten(scaled), format, quality);
                if (encoded.length <= target.byteBudget() || attempt == 3) {
                    return new RequestVariant(variantId, "image/" + format, encoded,
                            scaled.getWidth(), scaled.getHeight());
                }
                scale *= 0.8;
            }
        } catch (IOException e) {
            throw new AttachmentException("请求变体计算失败: " + e.getMessage());
        }
        throw new AttachmentException("请求变体计算失败");
    }

    /** 原子落缓存：tmp 写入后 move（REPLACE_EXISTING——并发写同 id 收敛到同字节）。 */
    private void writeCache(String variantId, byte[] data) {
        Path dir = cacheRoot.resolve(variantId.substring(0, 2));
        Path tmp = cacheRoot.resolve(variantId + ".tmp-" + Thread.currentThread().getId());
        try {
            Files.createDirectories(dir);
            Files.write(tmp, data);
            Files.move(tmp, dir.resolve(variantId), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 暂存清理失败无害
            }
            // 缓存写失败不阻断变体返回（缓存是优化）：仅记录，下一调用者重算
        }
    }
}
