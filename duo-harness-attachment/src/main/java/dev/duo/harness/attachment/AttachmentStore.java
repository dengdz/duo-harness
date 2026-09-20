package dev.duo.harness.attachment;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 内容寻址附件库（ADR-0022 决策 2）：规范化字节 SHA-256 寻址，
 * tmp fsync → 硬链接发布（EEXIST 比对去重）→ 0400 只读；无 GC 永不自动删除。
 * 布局：{@code <root>/objects/<2位>/<sha256>}，暂存 {@code <root>/tmp/}。
 *
 * <p>线程约定：无共享可变状态；发布由内核文件锁语义保证同 id 原子收敛
 * （硬链接 EEXIST 后比对 digest 确认同一对象）。</p>
 */
public final class AttachmentStore {

    /** 服务名（harness 保留裸名）。 */
    public static final String SERVICE_NAME = "attachments";

    private static final String HEX = "0123456789abcdef";

    private final Path root;
    private final AttachmentConfig config;

    public AttachmentStore(Path root, AttachmentConfig config) {
        this.root = root.toAbsolutePath().normalize();
        this.config = config;
    }

    /** 库根目录（{@code ~/.duo/attachments/v1}）。 */
    public Path root() {
        return root;
    }

    /** 规范对象路径（授权读取端点按 id 取字节用）。 */
    public Path objectPath(String attachmentId) {
        return root.resolve("objects").resolve(attachmentId.substring(0, 2)).resolve(attachmentId);
    }

    /** 附件是否已入库。 */
    public boolean exists(String attachmentId) {
        return attachmentId != null && attachmentId.length() == 64 && Files.exists(objectPath(attachmentId));
    }

    /** 准入并持久化一张图片：校验 → 规范化 → 寻址发布，返回附件元数据。 */
    public AdmittedImage storeImage(byte[] data, String declaredMediaType) {
        if (data == null || data.length == 0) {
            throw new AttachmentException("图片内容为空");
        }
        if (data.length > config.maxImageBytes()) {
            throw new AttachmentException("图片过大（" + data.length + " 字节超上限 " + config.maxImageBytes()
                    + "）——请压缩后再试");
        }
        String actual = ImageFormats.sniff(data)
                .orElseThrow(() -> new AttachmentException("不支持的图片格式（仅 png/jpeg/gif/webp）"));
        String declared = ImageFormats.normalizeDeclared(declaredMediaType).orElse(actual);
        if (!declared.equals(actual)) {
            throw new AttachmentException("声明的类型与实际内容不符（声明 " + declared + "，实际 " + actual + "）");
        }
        ImageNormalizer.Normalized normalized = ImageNormalizer.normalize(data, actual, config);
        String id = sha256Hex(normalized.data());
        publish(id, normalized.data());
        return new AdmittedImage(id, normalized.mediaType(), normalized.data().length,
                normalized.width(), normalized.height());
    }

    /** 发布协议：tmp fsync → 硬链接到位（EEXIST 比对 digest 确认同一对象）→ 0400 只读 → 清理暂存。 */
    private void publish(String id, byte[] data) {
        Path objects = root.resolve("objects").resolve(id.substring(0, 2));
        Path tmp = root.resolve("tmp").resolve(id + "-" + UUID.randomUUID() + ".tmp");
        Path target = objects.resolve(id);
        try {
            Files.createDirectories(tmp.getParent());
            Files.createDirectories(objects);
            try (FileChannel channel = FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(data));
                channel.force(true);
            }
            try {
                Files.createLink(target, tmp);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                verifySameObject(target, data); // 并发发布同名对象：内容不一致即库损坏，点名
            }
            markReadOnly(target);
        } catch (IOException e) {
            throw new AttachmentException("附件发布失败: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 暂存清理失败无害：tmp 下次写入自会覆盖
            }
        }
    }

    /** 并发/异常场景下已存在对象的完整性校验：digest 不符视为库损坏，宁可点名不可静默覆盖。 */
    private void verifySameObject(Path existing, byte[] expected) throws IOException {
        byte[] current = Files.readAllBytes(existing);
        if (!sha256Hex(current).equals(sha256Hex(expected))) {
            throw new AttachmentException("附件库对象冲突（" + existing.getFileName() + " 已存在且内容不一致）");
        }
    }

    /** 0400 只读（POSIX 文件系统；不支持的平台跳过——Windows 无此语义）。 */
    private void markReadOnly(Path target) throws IOException {
        try {
            Files.setPosixFilePermissions(target,
                    java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统无只读位：跳过（去重与内容寻址仍保证完整性）
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(digest.getDigestLength() * 2);
            for (byte b : digest.digest(data)) {
                sb.append(HEX.charAt((b >> 4) & 0xf)).append(HEX.charAt(b & 0xf));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用（JDK 必备算法）", e);
        }
    }
}
