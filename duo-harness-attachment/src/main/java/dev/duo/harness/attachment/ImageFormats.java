package dev.duo.harness.attachment;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 附件图片格式白名单与魔数嗅探：png/jpeg/gif/webp，声明与实际内容双重验证的事实来源。 */
final class ImageFormats {

    static final Set<String> SUPPORTED = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private ImageFormats() { }

    /** 魔数嗅探实际格式；识别不出返回 empty（由调用方拒绝）。 */
    static Optional<String> sniff(byte[] data) {
        if (data == null || data.length < 12) {
            return Optional.empty();
        }
        if ((data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == '\r' && data[5] == '\n' && data[6] == 0x1a && data[7] == '\n') {
            return Optional.of("image/png");
        }
        if ((data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8 && (data[2] & 0xff) == 0xff) {
            return Optional.of("image/jpeg");
        }
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8') {
            return Optional.of("image/gif");
        }
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    /** 声明类型规范化（小写）；白名单外返回 empty。 */
    static Optional<String> normalizeDeclared(String declared) {
        if (declared == null || declared.isBlank()) {
            return Optional.empty();
        }
        String type = declared.strip().toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        if (semicolon > 0) {
            type = type.substring(0, semicolon).strip();
        }
        return SUPPORTED.contains(type) ? Optional.of(type) : Optional.empty();
    }
}
