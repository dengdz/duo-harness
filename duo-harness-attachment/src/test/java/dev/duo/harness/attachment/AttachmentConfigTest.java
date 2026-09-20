package dev.duo.harness.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.PluginException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 附件配置解析：缺省对齐 DSH 量级、非法值 FAILED 点名、>2GB 字节上限不被 int 截断。 */
class AttachmentConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：AttachmentConfigTest —— 附件配置解析（缺省/非法/长整型） ===");
    }

    @Test
    void 缺省配置对齐DSH量级() {
        AttachmentConfig cfg = AttachmentConfig.parse(null);
        assertEquals(20L * 1024 * 1024, cfg.maxImageBytes());
        assertEquals(20, cfg.maxImagesPerMessage());
        assertEquals(100L * 1024 * 1024, cfg.maxMessageImageBytes());
        assertEquals(2048L * 2048, cfg.normalizedImageMaxPixels());
    }

    @Test
    void 大字节上限不被int截断() throws Exception {
        AttachmentConfig cfg = AttachmentConfig.parse(MAPPER.readTree(
                "{\"maxImageBytes\": 3000000000, \"normalizedImageMaxBytes\": 5000000000}"));
        assertEquals(3_000_000_000L, cfg.maxImageBytes());
        assertEquals(5_000_000_000L, cfg.normalizedImageMaxBytes());
    }

    @Test
    void 非法值启动点名() throws Exception {
        assertThrows(PluginException.class,
                () -> AttachmentConfig.parse(MAPPER.readTree("{\"maxImageBytes\": -1}")));
        assertThrows(PluginException.class,
                () -> AttachmentConfig.parse(MAPPER.readTree("{\"maxImagesPerMessage\": \"many\"}")));
    }
}
