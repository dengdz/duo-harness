package dev.duo.harness.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M38 工单 02 回归锁（单元层，写回核不带 HTTP 面——端点契约见 web 模块
 * WebLlmConfigEndpointTest）：LlmConfigFile llm 段文本手术——非 llm 段字节级
 * 保留 / 无 llm 块追加 / 校验不过原文件不动不留残件（provider 越界、models 坏条目）。
 */
class LlmConfigFileTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：LlmConfigFileTest —— llm 段写回：文本手术/原子改名/校验点名（M38-02） ===");
    }

    @TempDir
    Path tempDir;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final String FIXTURE = """
            # 用户注释行（llm 块外应字节保留）
            llm:
              baseUrl: https://api.original.com
              apiKey: original-key-123
              model: orig-model
              models:
              - orig-model
            other:
              keep: true
            """;

    private Path writeFixture(String content) throws Exception {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, content);
        return file;
    }

    private com.fasterxml.jackson.databind.JsonNode llmNode(String yml) throws Exception {
        return YAML.readTree(yml).get("llm");
    }

    @Test
    void writeRewritesLlmBlockPreservingNonLlmBytes() throws Exception {
        Path file = writeFixture(FIXTURE);
        LlmConfigFile.writeLlmSection(file, llmNode("""
                llm:
                  baseUrl: https://api.new.com
                  apiKey: new-key-456
                  model: new-model
                  models:
                  - new-model
                  - second-model
                """));
        String after = Files.readString(file);
        assertTrue(after.startsWith("# 用户注释行（llm 块外应字节保留）\n"),
                "llm 块前内容（含注释）原样在前: " + after);
        assertTrue(after.contains("other:\n  keep: true\n"), "other 段原样保留");
        assertFalse(after.contains("api.original.com"), "旧 baseUrl 不残留");
        // 重读一致（写回的文件 boot 可直接装载）
        var llm = LlmConfigFile.readLlmNode(file);
        assertEquals("https://api.new.com", llm.path("baseUrl").asText());
        assertEquals("new-key-456", llm.path("apiKey").asText());
        assertEquals(2, llm.path("models").size(), "白名单整组替换");
        assertEquals("second-model", llm.path("models").get(1).asText());
        assertNoTmpResidue();
    }

    @Test
    void writeAppendsLlmBlockWhenFileHasNone() throws Exception {
        Path file = writeFixture("other:\n  keep: true\n");
        LlmConfigFile.writeLlmSection(file, llmNode("""
                llm:
                  baseUrl: https://api.new.com
                  apiKey: k
                  model: m
                """));
        String after = Files.readString(file);
        assertTrue(after.startsWith("other:\n  keep: true\n"), "无 llm 块：既有内容在前原样");
        // 值形态为 Jackson 重序列化（带引号），断结构不断裸值——装载一致才是契约
        assertTrue(after.contains("\nllm:\n") && after.strip().endsWith("model: \"m\""),
                "llm 块追加在后（前有空行分隔）: " + after);
        assertEquals("https://api.new.com",
                LlmConfig.load(file, java.util.Map.of()).baseUrl(), "追加后可装载");
    }

    @Test
    void invalidProviderThrowsAndOriginalUntouched() throws Exception {
        Path file = writeFixture(FIXTURE);
        assertThrows(dev.duo.harness.core.api.PluginException.class,
                () -> LlmConfigFile.writeLlmSection(file, llmNode("""
                        llm:
                          provider: foo-provider
                          baseUrl: https://api.new.com
                          apiKey: k
                          model: m
                        """)));
        assertEquals(FIXTURE, Files.readString(file), "校验不过原文件字节级不动");
        assertNoTmpResidue();
    }

    @Test
    void invalidModelsEntryThrowsAndOriginalUntouched() throws Exception {
        Path file = writeFixture(FIXTURE);
        assertThrows(dev.duo.harness.core.api.PluginException.class,
                () -> LlmConfigFile.writeLlmSection(file, llmNode("""
                        llm:
                          baseUrl: https://api.new.com
                          apiKey: k
                          model: m
                          models:
                          - ""
                        """)));
        assertEquals(FIXTURE, Files.readString(file), "坏白名单条目原文件不动（启动即 FAILED 同口径前移）");
        assertNoTmpResidue();
    }

    @Test
    void readLlmNodeReturnsNullForMissingFileOrSection() throws Exception {
        assertNull(LlmConfigFile.readLlmNode(tempDir.resolve("absent.yml")), "文件缺失 null");
        Path noLlm = writeFixture("other:\n  keep: true\n");
        assertNull(LlmConfigFile.readLlmNode(noLlm), "无 llm 段 null（调用方按空配置处理）");
    }

    /** 写回失败/成功都不留临时残件（校验抛出与改名分支的 finally 兜底）。 */
    private void assertNoTmpResidue() throws Exception {
        try (Stream<Path> list = Files.list(tempDir)) {
            assertEquals(0, list.filter(p -> p.getFileName().toString().endsWith(".tmp")).count(),
                    "不留 .tmp 残件");
        }
    }
}
