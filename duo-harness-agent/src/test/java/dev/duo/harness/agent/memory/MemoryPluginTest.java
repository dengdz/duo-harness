package dev.duo.harness.agent.memory;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.agent.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆插件装配用例（M25 工单 02，Boot.from + @TempDir 装配级先例；user.dir 覆盖为
 * 本套件约定——测后恢复）：服务发布与视图可达、规范段随功能可用注册/全不可用
 * 不注册、预算配置生效、写通道装配（工具在册 + 写入即生效）。
 */
class MemoryPluginTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：MemoryPluginTest —— 记忆插件装配：服务发布、"
                + "规范段注册、预算配置 + 写通道装配（4 用例） ===");
    }

    /** 服务视图接口（方法名即服务名 "memory"——camelCase 惯例）。 */
    interface MemoryView {

        MemoryBook memory();
    }

    interface PromptsView {

        PromptRegistry prompts();
    }

    interface ToolsView {

        dev.duo.harness.tools.ToolsService tools();
    }

    @TempDir
    Path tempDir;

    private Path writeYml(String memoryConfig, boolean withTools) throws IOException {
        Path yml = tempDir.resolve("boot-memory-test.yml");
        String toolsRow = withTools
                ? "  - id: tools\n    name: dev.duo.harness.tools.ToolsPlugin\n"
                : "";
        Files.writeString(yml, """
                plugins:
                  - id: prompts
                    name: dev.duo.harness.agent.prompt.PromptPlugin
                    config:
                      systemPrompt: 测试指令
                %s  - id: memory
                    name: dev.duo.harness.agent.memory.MemoryPlugin
                    config: %s
                """.formatted(toolsRow, memoryConfig));
        return yml;
    }

    @Test
    void 记忆本在场装配发布服务与规范段() throws IOException {
        Files.createDirectories(tempDir.resolve(".duo"));
        Files.writeString(tempDir.resolve(".duo").resolve("MEMORY.md"), "- 项目偏好中文回复\n");
        Path yml = writeYml("{}", false);
        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        Context root = null;
        try {
            root = dev.duo.harness.core.api.boot.Boot.from(yml);
            MemoryBook memory = root.as(MemoryView.class).memory();
            assertNotNull(memory, "memory 服务应随 memory 行发布");
            assertTrue(memory.read().contains("项目偏好中文回复"), "服务现读记忆本: " + memory.read());
            assertTrue(root.as(PromptsView.class).prompts().hasSource("memory-guide"),
                    "记忆本在场 → 规范段注册进 prompt 注册表");
        } finally {
            System.setProperty("user.dir", originalDir);
            if (root != null) {
                root.dispose();
            }
        }
    }

    @Test
    void 记忆本缺席装配零感降级() throws IOException {
        Path yml = writeYml("{}", false);
        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        Context root = null;
        try {
            root = dev.duo.harness.core.api.boot.Boot.from(yml);
            MemoryBook memory = root.as(MemoryView.class).memory();
            assertNotNull(memory, "未启用（无文件）服务照常发布——读路径静默降级");
            assertNull(memory.read(), "无记忆本 → 现读为 null");
            assertFalse(root.as(PromptsView.class).prompts().hasSource("memory-guide"),
                    "无记忆本 → 规范段不注册（无约定即无注入）");
        } finally {
            System.setProperty("user.dir", originalDir);
            if (root != null) {
                root.dispose();
            }
        }
    }

    @Test
    void 预算配置生效() throws IOException {
        Files.createDirectories(tempDir.resolve(".duo"));
        Files.writeString(tempDir.resolve(".duo").resolve("MEMORY.md"), "y".repeat(100));
        Path yml = writeYml("{budgetChars: 20}", false);
        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        Context root = null;
        try {
            root = dev.duo.harness.core.api.boot.Boot.from(yml);
            String read = root.as(MemoryView.class).memory().read();
            assertTrue(read.length() < 100, "配置预算应截断: " + read.length());
            assertTrue(read.contains("已截断"), "截断标注: " + read);
            assertEquals(20, root.as(MemoryView.class).memory().budgetChars(), "配置透传");
        } finally {
            System.setProperty("user.dir", originalDir);
            if (root != null) {
                root.dispose();
            }
        }
    }

    @Test
    void 写通道装配工具在册且写入即生效() throws Exception {
        // 记忆本缺席 + tools 在场：写路径可用即注册 memory_write 与 guide（工单 03
        // 收敛）——首次"记住 X"直接创建文件，注入随之生效
        Path yml = writeYml("{}", true);
        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        Context root = null;
        try {
            root = dev.duo.harness.core.api.boot.Boot.from(yml);
            dev.duo.harness.tools.ToolsService tools = root.as(ToolsView.class).tools();
            assertTrue(tools.list().stream().anyMatch(d -> MemoryWriteTool.NAME.equals(d.name())),
                    "tools 行在场 → memory_write 在册: "
                            + tools.list().stream().map(d -> d.name()).toList());
            assertTrue(root.as(PromptsView.class).prompts().hasSource("memory-guide"),
                    "写通道可用 → 规范段注册");

            // 走真管线写入：requiresApproval=false 无审批直达，append 落盘
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var result = tools.execute("memory_write",
                    mapper.readTree("{\"content\":\"记住：验收走 memory_write\"}"));
            assertFalse(result.isError(), "无审批写入应成功: " + result.value());
            assertTrue(String.valueOf(result.value()).contains("已记入记忆本"), "确认语: " + result.value());

            dev.duo.harness.agent.memory.MemoryBook memory = root.as(MemoryView.class).memory();
            assertTrue(memory.read().contains("记住：验收走 memory_write"), "写入后读路径现读即见");
            assertTrue(memory.metaUserSection().contains("记住：验收走 memory_write"),
                    "写后注入段即含新条目（下轮请求模型可见）");
            assertEquals(1, memory.entryCount(), "条目计数");
        } finally {
            System.setProperty("user.dir", originalDir);
            if (root != null) {
                root.dispose();
            }
        }
    }
}
