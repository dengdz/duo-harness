package dev.duo.harness.agent.internal;

import dev.duo.harness.attachment.AdmittedImage;
import dev.duo.harness.attachment.AttachmentConfig;
import dev.duo.harness.attachment.AttachmentStore;
import dev.duo.harness.attachment.FilesApiUploader;
import dev.duo.harness.attachment.ImageFileDelivery;
import dev.duo.harness.attachment.RequestVariants;
import dev.duo.harness.llm.ChatMessage;
import dev.duo.harness.session.AttachmentRef;
import dev.duo.harness.session.Message;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * files 投递的消息投影（M21 工单 06）：delivery 命中 → file_id 部件；上传失败 →
 * 整体回退 inline base64（ADR-0022 决策 5——投递优化不添堵）。
 */
class MessagesFileDeliveryTest {

    @TempDir
    Path tmp;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：MessagesFileDeliveryTest —— files 投递消息投影：命中/回退 ===");
    }

    private static byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.RED, 32, 32, Color.BLUE));
        g.fillRect(0, 0, 32, 32);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** 夹具：附件库含一张已入库图片（引用 + 变体解析器就绪）。 */
    private record Fixture(AttachmentStore store, AttachmentRef ref) {

        RequestVariants variants(Path cacheDir) {
            return new RequestVariants(store, cacheDir);
        }

        List<Message> projected() {
            return List.of(Message.userWithAttachments("看图", List.of(ref)));
        }
    }

    private Fixture fixture() throws Exception {
        AttachmentConfig cfg = new AttachmentConfig(20_971_520, 20, 104_857_600,
                67_108_864, 8192, 4_194_304, 8192, 4_194_304);
        AttachmentStore store = new AttachmentStore(tmp.resolve("att/v1"), cfg);
        AdmittedImage admitted = store.storeImage(png(), "image/png");
        AttachmentRef ref = new AttachmentRef(admitted.attachmentId(), "image/png", 2048, "shot.png");
        return new Fixture(store, ref);
    }

    @Test
    void deliveryHitProducesFileIdPart() throws Exception {
        Fixture fixture = fixture();
        ImageFileDelivery delivery = new StubDelivery("file-ok", null);

        List<ChatMessage> out = Messages.toChatMessages(
                fixture.projected(), fixture.variants(tmp.resolve("cache")), true, delivery);
        ChatMessage user = out.get(0);
        assertEquals(1, user.images().size());
        assertTrue(user.images().get(0).deliveredAsFile());
        assertEquals("file-ok", user.images().get(0).fileId());
    }

    @Test
    void uploadFailureFallsBackToInline() throws Exception {
        Fixture fixture = fixture();
        ImageFileDelivery delivery = new StubDelivery(null,
                new FilesApiUploader.FilesApiException("Files API 返回 HTTP 500: boom"));

        List<ChatMessage> out = Messages.toChatMessages(
                fixture.projected(), fixture.variants(tmp.resolve("cache")), true, delivery);
        ChatMessage user = out.get(0);
        assertEquals(1, user.images().size());
        assertTrue(!user.images().get(0).deliveredAsFile()); // 回退 inline
        assertTrue(user.images().get(0).dataUri().startsWith("data:image/")); // 规范化可能重编码 jpeg
    }

    /** 可编程桩：命中返回固定 file_id 或固定上抛（不触真实 HTTP）。 */
    private static final class StubDelivery extends ImageFileDelivery {
        private final String fileId;
        private final FilesApiUploader.FilesApiException failure;

        StubDelivery(String fileId, FilesApiUploader.FilesApiException failure) {
            super(new FilesApiUploader("http://localhost:1", "k", java.time.Duration.ofSeconds(1)),
                    Path.of("/tmp/duo-stub-files-index-does-not-exist.json"));
            this.fileId = fileId;
            this.failure = failure;
        }

        @Override
        public synchronized String deliver(String variantId, byte[] bytes,
                                           String mediaType, String fileName) {
            if (failure != null) {
                throw failure;
            }
            return fileId;
        }
    }
}
