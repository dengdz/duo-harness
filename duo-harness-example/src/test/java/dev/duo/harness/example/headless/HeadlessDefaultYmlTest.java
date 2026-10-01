package dev.duo.harness.example.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺省装配资源通道用例（M30 工单 01）：headless 缺省分支经 classpath 流读取
 * 装配文本（jar 形态不再文件化 Path），预过滤机制（临时副本）原样保留。
 */
class HeadlessDefaultYmlTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：HeadlessDefaultYmlTest —— 缺省装配资源流读取：文本可过滤、"
                + "缺失点名、useDefaultYml 分支（4 用例） ===");
    }

    @TempDir
    Path tempDir;

    @Test
    void defaultYmlTextReadableAndFilterable() throws Exception {
        String text = HeadlessArgs.defaultYmlText();
        assertTrue(text.contains("plugins:"), "缺省装配文本应含 plugins 结构");
        Path filtered = HeadlessBoot.filteredCopy(text);
        assertTrue(Files.isRegularFile(filtered));
        assertPresenterRowsDisabled(filtered);
    }

    @Test
    void filteredCopyByTextAndByPathAgree() throws Exception {
        String text = """
                plugins:
                  - id: web
                    name: dev.duo.harness.web.WebPlugin
                  - id: cli
                    name: dev.duo.harness.cli.CliPlugin
                  - id: tools
                    name: dev.duo.harness.tools.ToolsPlugin
                """;
        Path source = tempDir.resolve("assembly.yml");
        Files.writeString(source, text);

        Path byText = HeadlessBoot.filteredCopy(text);
        Path byPath = HeadlessBoot.filteredCopy(source);
        assertEquals(Files.readString(byText), Files.readString(byPath));

        assertPresenterRowsDisabled(byText);
        JsonNode tools = readRow(byText, "dev.duo.harness.tools.ToolsPlugin");
        assertFalse(tools.path("disabled").asBoolean(false), "非呈现位行不应被禁用");
    }

    @Test
    void resourceYmlTextMissingThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> HeadlessArgs.resourceYmlText("/no/such-assembly.yml"));
        assertTrue(e.getMessage().contains("缺省装配资源"), e.getMessage());
    }

    @Test
    void useDefaultYmlBranchesOnExplicitPath() throws Exception {
        assertTrue(HeadlessArgs.parse(new String[0]).useDefaultYml());
        assertTrue(HeadlessArgs.parse(new String[]{"--json", "跑个任务"}).useDefaultYml());

        Path explicit = tempDir.resolve("custom.yml");
        Files.writeString(explicit, "plugins: []");
        assertFalse(HeadlessArgs.parse(new String[]{explicit.toString(), "跑个任务"})
                .useDefaultYml());
    }

    // === 工具 ===

    private static void assertPresenterRowsDisabled(Path filtered) throws Exception {
        for (String presenter : HeadlessBoot.PRESENTER_ROWS) {
            JsonNode row = readRow(filtered, presenter);
            assertTrue(row.path("disabled").asBoolean(false),
                    "呈现位行应被标 disabled: " + presenter);
        }
    }

    private static JsonNode readRow(Path filtered, String pluginName) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        JsonNode tree = mapper.readTree(Files.readString(filtered));
        JsonNode plugins = tree.get("plugins");
        assertTrue(plugins instanceof ArrayNode, "过滤副本应保持 plugins 列表结构");
        for (JsonNode row : (ArrayNode) plugins) {
            if (row instanceof ObjectNode pluginRow
                    && pluginRow.path("name").asText().equals(pluginName)) {
                return pluginRow;
            }
        }
        throw new AssertionError("未找到插件行: " + pluginName);
    }
}
