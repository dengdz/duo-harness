package dev.duo.harness.attachment;

import net.coobird.thumbnailator.Thumbnails;

import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * 图片规范化（ADR-0022 决策 3）：解码三重校验（魔数/声明/可解码）→ 源预算检查 →
 * 预算内原样保留（png/jpeg/gif）、超预算缩放重编码（透明底转白底的 png/jpeg）。
 * 重编码损失由"超预算才转"控制，预算内不折腾（无损保真）。
 */
final class ImageNormalizer {

    record Normalized(byte[] data, String mediaType, int width, int height) {
    }

    /** 重编码兜底的最大尝试次数（超预算逐次缩边降质）。 */
    private static final int MAX_ENCODE_ATTEMPTS = 4;

    private ImageNormalizer() { }

    /** 解码 + 校验 + 规范化，返回规范对象字节与最终尺寸。 */
    static Normalized normalize(byte[] data, String mediaType, AttachmentConfig config) {
        BufferedImage image = decode(data);
        long pixels = (long) image.getWidth() * image.getHeight();
        int longEdge = Math.max(image.getWidth(), image.getHeight());
        if (pixels > config.maxImagePixels()) {
            throw new AttachmentException("图片像素超限（" + pixels + " > " + config.maxImagePixels()
                    + "）——请缩小后再试");
        }
        if (longEdge > config.maxImageDimension()) {
            throw new AttachmentException("图片边长超限（" + longEdge + " > " + config.maxImageDimension()
                    + "）——请缩小后再试");
        }
        // 预算内且本身就是可长期保存的格式（png/jpeg/gif）→ 原样字节即规范对象（无损）
        boolean withinBudget = data.length <= config.normalizedImageMaxBytes()
                && pixels <= config.normalizedImageMaxPixels()
                && longEdge <= config.normalizedImageMaxDimension();
        if (withinBudget && !mediaType.equals("image/webp")) {
            return new Normalized(data, mediaType, image.getWidth(), image.getHeight());
        }
        // 超预算或 webp（无重编码支持）→ 缩放重编码：透明通道走 png，其余走 jpeg（白底合成）
        boolean alpha = image.getColorModel().hasAlpha();
        String format = alpha ? "png" : "jpeg";
        return transform(image, format, config);
    }

    private static BufferedImage decode(byte[] data) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) {
                throw new AttachmentException("无法解码图片内容（格式不受支持或文件损坏）");
            }
            return image;
        } catch (IOException e) {
            throw new AttachmentException("图片解码失败: " + e.getMessage());
        }
    }

    /** 缩放至规范化预算（像素 + 边长），重编码至字节预算内；逐次缩边降质，封顶尝试后接受。 */
    private static Normalized transform(BufferedImage source, String format, AttachmentConfig config) {
        boolean alpha = source.getColorModel().hasAlpha();
        // 初始 scale 由像素/边长预算驱动（只缩不放）：字节预算超限时循环内再逐次缩边
        double pixels = (long) source.getWidth() * source.getHeight();
        double scale = Math.min(
                Math.sqrt(config.normalizedImageMaxPixels() / pixels),
                (double) config.normalizedImageMaxDimension() / Math.max(source.getWidth(), source.getHeight()));
        if (scale > 1.0) {
            scale = 1.0;
        }
        for (int attempt = 0; attempt < MAX_ENCODE_ATTEMPTS; attempt++) {
            int targetW = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int targetH = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage scaled;
            try {
                scaled = scale < 1.0
                        ? Thumbnails.of(source).size(targetW, targetH).asBufferedImage() : source;
            } catch (IOException e) {
                throw new AttachmentException("图片缩放失败: " + e.getMessage());
            }
            double quality = Math.max(0.4, 0.9 - attempt * 0.2);
            byte[] encoded = encode(alpha || format.equals("png") ? scaled : flatten(scaled),
                    format, quality);
            if (encoded.length <= config.normalizedImageMaxBytes() || attempt == MAX_ENCODE_ATTEMPTS - 1) {
                return new Normalized(encoded, "image/" + format, scaled.getWidth(), scaled.getHeight());
            }
            scale *= 0.8;
        }
        throw new AttachmentException("图片规范化失败——请缩小后再试");
    }

    /** 质量参数编码（包内复用：请求变体管线同款编码语义）。 */
    static byte[] encode(BufferedImage image, String format, double quality) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Thumbnails.of(image).scale(1.0).outputQuality(quality).outputFormat(format).toOutputStream(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new AttachmentException("图片重编码失败: " + e.getMessage());
        }
    }

    /** 透明通道合成白底（JPEG 不支持 alpha，直接编码会得到黑底；包内复用）。 */
    static BufferedImage flatten(BufferedImage source) {
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(source, 0, 0, Color.WHITE, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }
}
