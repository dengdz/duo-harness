package dev.duo.harness.attachment;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求变体管线测试：variantId 确定性、字节预算适配、缓存命中不重写（inode 不变）、
 * 并发单飞（N 路并发一次计算）、附件缺失拒绝。
 */
class RequestVariantsTest {

    @TempDir
    Path tempDir;

    private AttachmentStore store;
    private Path cacheRoot;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：RequestVariantsTest —— 请求变体：确定性/预算适配/缓存/并发单飞 ===");
    }

    private AttachmentStore newStore() throws IOException {
        if (store == null) {
            store = new AttachmentStore(tempDir.resolve("v1"), AttachmentConfig.defaults());
        }
        return store;
    }

    private Path newCache() {
        if (cacheRoot == null) {
            cacheRoot = tempDir.resolve("cache");
        }
        return cacheRoot;
    }

    private String storeGradient(int w, int h) throws IOException {
        newStore(); // 惰性初始化在首次使用前完成
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.MAGENTA, w, h, Color.CYAN));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return store.storeImage(out.toByteArray(), "image/png").attachmentId();
    }

    @Test
    void 同附件同目标两次调用字节逐位一致() throws Exception {
        String id = storeGradient(800, 600);
        RequestVariants variants = new RequestVariants(newStore(), newCache());

        RequestVariant first = variants.variantFor(id, new VariantTarget(512, 512 * 1024));
        RequestVariant second = variants.variantFor(id, new VariantTarget(512, 512 * 1024));

        assertEquals(first.variantId(), second.variantId());
        assertArrayEquals(first.bytes(), second.bytes(), "确定性：同附件同目标必得同变体字节");
        assertTrue(first.width() <= 512 && first.height() <= 512, "长边不超目标: "
                + first.width() + "x" + first.height());
        assertTrue(first.bytes().length <= 512 * 1024);
    }

    @Test
    void 不同目标得不同变体() throws Exception {
        String id = storeGradient(800, 600);
        RequestVariants variants = new RequestVariants(newStore(), newCache());

        RequestVariant big = variants.variantFor(id, new VariantTarget(512, 4L * 1024 * 1024));
        RequestVariant small = variants.variantFor(id, new VariantTarget(128, 4L * 1024 * 1024));

        assertNotEquals(big.variantId(), small.variantId());
        assertTrue(small.width() <= 128 && small.height() <= 128);
    }

    @Test
    void 字节预算适配生效() throws Exception {
        String id = storeGradient(1200, 900);
        RequestVariants variants = new RequestVariants(newStore(), newCache());

        // 预算给到 24KB：质量阶梯必须压进预算（或封顶尝试后仍尽力贴近）
        RequestVariant variant = variants.variantFor(id, new VariantTarget(1024, 24 * 1024));
        assertTrue(variant.bytes().length <= 32 * 1024,
                "预算 24KB 应压进约 24KB（封顶容差）: " + variant.bytes().length);
    }

    @Test
    void 缓存命中不重写文件() throws Exception {
        String id = storeGradient(600, 400);
        RequestVariants variants = new RequestVariants(newStore(), newCache());
        VariantTarget target = new VariantTarget(256, 1024 * 1024);

        RequestVariant first = variants.variantFor(id, target);
        Path cacheFile = newCache().resolve(first.variantId().substring(0, 2)).resolve(first.variantId());
        assertTrue(Files.isRegularFile(cacheFile), "变体应落缓存");
        Object inodeBefore = Files.getAttribute(cacheFile, "unix:ino");

        RequestVariant second = variants.variantFor(id, target);

        assertArrayEquals(first.bytes(), second.bytes());
        assertEquals(inodeBefore, Files.getAttribute(cacheFile, "unix:ino"),
                "缓存命中不得重写文件（inode 不变 = 零计算）");
    }

    @Test
    void 并发同变体单飞只计算一次() throws Exception {
        String id = storeGradient(700, 500);
        AtomicInteger computations = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        RequestVariants counting = new RequestVariants(newStore(), newCache()) {
            @Override
            RequestVariant computeAndCache(String attachmentId, VariantTarget target, String variantId) {
                computations.incrementAndGet();
                try {
                    startGate.await(); // 拉宽竞态窗口：首个计算者挂起，其余并发者必须等同一 future
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.computeAndCache(attachmentId, target, variantId);
            }
        };
        VariantTarget target = new VariantTarget(300, 1024 * 1024);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<RequestVariant>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> counting.variantFor(id, target)));
            }
            // 等全部调用者进入（首个在计算、7 个在等 future）后放行
            Thread.sleep(300);
            startGate.countDown();
            RequestVariant first = futures.get(0).get(10, TimeUnit.SECONDS);
            for (Future<RequestVariant> future : futures) {
                assertArrayEquals(first.bytes(), future.get(10, TimeUnit.SECONDS).bytes());
            }
            assertEquals(1, computations.get(), "8 路并发只应计算一次: " + computations.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 附件缺失拒绝() throws Exception {
        RequestVariants variants = new RequestVariants(newStore(), newCache());
        AttachmentException e = assertThrows(AttachmentException.class,
                () -> variants.variantFor("0".repeat(64), new VariantTarget(256, 1024)));
        assertTrue(e.getMessage().contains("附件不存在"));
    }
}
