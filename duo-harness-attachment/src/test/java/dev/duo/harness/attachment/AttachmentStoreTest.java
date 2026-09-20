package dev.duo.harness.attachment;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件库直调测试（seam ①，主战场）：内容寻址与去重、规范化、准入拒绝矩阵、
 * 0400 只读。测试图全部代码生成（TwelveMonkeys/Thumbnailator 编码），零真实图片资源。
 */
class AttachmentStoreTest {

    /** 1×1 无损 WebP 夹具（base64，公开测试样本）。 */
    private static final String WEBP_FIXTURE_BASE64 =
            "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

    @TempDir
    Path tempDir;

    private Path root;
    private AttachmentStore store;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：AttachmentStoreTest —— 附件库：内容寻址/去重/规范化/拒绝矩阵 ===");
    }

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("attachments/v1");
        store = new AttachmentStore(root, AttachmentConfig.defaults());
    }

    private static byte[] png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.RED, w, h, Color.BLUE));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return imageBytes(img, "png");
    }

    private static byte[] pngWithAlpha(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(255, 0, 0, 128), w, h, Color.GREEN));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return imageBytes(img, "png");
    }

    private static byte[] jpeg(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.YELLOW, w, h, Color.CYAN));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return imageBytes(img, "jpeg");
    }

    private static byte[] gif(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return imageBytes(img, "gif");
    }

    private static byte[] imageBytes(BufferedImage img, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    @Test
    void png入库内容寻址与元数据() throws IOException {
        byte[] data = png(64, 32);
        AdmittedImage admitted = store.storeImage(data, "image/png");

        assertEquals("image/png", admitted.mediaType());
        assertEquals(64, admitted.width());
        assertEquals(32, admitted.height());
        assertTrue(admitted.bytes() > 0);
        assertTrue(store.exists(admitted.attachmentId()), "对象应已入库");
        assertTrue(Files.exists(store.objectPath(admitted.attachmentId())));
    }

    @Test
    void 同图重复入库去重为单对象() throws Exception {
        byte[] data = png(48, 48);
        AdmittedImage first = store.storeImage(data, "image/png");
        AdmittedImage second = store.storeImage(data, "image/png");

        assertEquals(first.attachmentId(), second.attachmentId(), "同图同 id（内容寻址去重）");
        try (var objects = Files.list(root.resolve("objects").resolve(first.attachmentId().substring(0, 2)))) {
            assertEquals(1, objects.count(), "objects 目录应只有单对象");
        }
        assertArrayEquals(data, Files.readAllBytes(store.objectPath(first.attachmentId())));
    }

    @Test
    void 对象发布为0400只读() throws IOException {
        byte[] data = png(32, 32);
        AdmittedImage admitted = store.storeImage(data, "image/png");
        if (store.objectPath(admitted.attachmentId()).getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("r--------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(store.objectPath(admitted.attachmentId()))),
                    "对象应为 0400 只读");
        }
    }

    @Test
    void 声明与实际不符拒绝() throws IOException {
        byte[] pngData = png(16, 16);
        AttachmentException e = assertThrows(AttachmentException.class,
                () -> store.storeImage(pngData, "image/gif"));
        assertTrue(e.getMessage().contains("不符"), "应点名声明与实际不符: " + e.getMessage());
    }

    @Test
    void 非图片字节拒绝() {
        AttachmentException e = assertThrows(AttachmentException.class,
                () -> store.storeImage("这不是图片".getBytes(), "image/png"));
        assertTrue(e.getMessage().contains("不支持的图片格式"));
    }

    @Test
    void 空内容拒绝() {
        AttachmentException e = assertThrows(AttachmentException.class,
                () -> store.storeImage(new byte[0], "image/png"));
        assertTrue(e.getMessage().contains("为空"));
    }

    @Test
    void 超源字节上限拒绝() throws IOException {
        AttachmentStore tiny = new AttachmentStore(root.resolve("tiny"),
                new AttachmentConfig(100, 5, 1_000_000, 64_000_000, 8192, 4_194_304, 8192, 4_194_304));
        byte[] big = png(200, 200);
        AttachmentException e = assertThrows(AttachmentException.class,
                () -> tiny.storeImage(big, "image/png"));
        assertTrue(e.getMessage().contains("图片过大"), "源字节超限应点名: " + e.getMessage());
    }

    @Test
    void 像素超限拒绝() throws IOException {
        AttachmentStore tiny = new AttachmentStore(root.resolve("pix"),
                new AttachmentConfig(20_000_000, 5, 100_000_000, 100, 8192, 4_194_304, 8192, 4_194_304));
        byte[] big = png(100, 100); // 10000 像素 > 100 预算
        AttachmentException e = assertThrows(AttachmentException.class,
                () -> tiny.storeImage(big, "image/png"));
        assertTrue(e.getMessage().contains("像素超限"), "像素超限应点名: " + e.getMessage());
    }

    @Test
    void 超预算图片缩放规范化() throws IOException {
        // 3000×2000 = 6M 像素，超 2048² 规范化预算 → 应缩放重编码
        AttachmentStore tight = new AttachmentStore(root.resolve("norm"),
                new AttachmentConfig(20_000_000, 5, 100_000_000, 64_000_000, 8192, 1_000_000, 8192, 4_194_304));
        AdmittedImage admitted = tight.storeImage(png(3000, 2000), "image/png");

        assertTrue((long) admitted.width() * admitted.height() <= 1_000_000,
                "规范对象应缩至像素预算内: " + admitted.width() + "x" + admitted.height());
    }

    @Test
    void webp夹具解码入库() throws IOException {
        byte[] webp = Base64.getDecoder().decode(WEBP_FIXTURE_BASE64);
        AdmittedImage admitted = store.storeImage(webp, "image/webp");

        assertEquals("image/png", admitted.mediaType(), "webp 无重编码支持——规范化转 png");
        assertTrue(store.exists(admitted.attachmentId()));
    }

    @Test
    void gif预算内原样保留() throws IOException {
        byte[] data = gif(20, 20);
        AdmittedImage admitted = store.storeImage(data, "image/gif");
        assertEquals("image/gif", admitted.mediaType(), "预算内 gif 原样保留");
        assertArrayEquals(data, Files.readAllBytes(store.objectPath(admitted.attachmentId())));
    }

    @Test
    void 不同图不同id() throws IOException {
        AdmittedImage a = store.storeImage(png(16, 16), "image/png");
        AdmittedImage b = store.storeImage(jpeg(16, 16), "image/jpeg");
        assertNotEquals(a.attachmentId(), b.attachmentId());
    }
}
