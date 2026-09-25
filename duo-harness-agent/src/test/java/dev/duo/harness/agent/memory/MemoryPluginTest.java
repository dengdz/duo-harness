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
 * 本套件约定——测后恢复）：服务发布与视图可达、规范段随记忆本在场注册/缺席不注册、预算配置生效。
 */
class MemoryPluginTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：MemoryPluginTest —— 记忆插件装配：服务发布、规范段注册、预算配置（3 用例） ===");
    }

    /** 服务视图接口（方法名即服务名 "memory"——camelCase 惯例）。 */
    interface MemoryView {

        MemoryBook memory();
    }

    interface PromptsView {

        PromptRegistry prompts();
    }

    @TempDir
    Path tempDir;

    private Path writeYml(String memoryConfig) throws IOException {
        Path yml = tempDir.resolve("boot-memory-test.yml");
        Files.writeString(yml, """
                plugins:
                  - id: prompts
                    name: dev.duo.harness.agent.prompt.PromptPlugin
                    config:
                      systemPrompt: 测试指令
                  - id: memory
                    name: dev.duo.harness.agent.memory.MemoryPlugin
                    config: %s
                """.formatted(memoryConfig));
        return yml;
    }

    @Test
    void 记忆本在场装配发布服务与规范段() throws IOException {
        Files.createDirectories(tempDir.resolve(".duo"));
        Files.writeString(tempDir.resolve(".duo").resolve("MEMORY.md"), "- 项目偏好中文回复\n");
        Path yml = writeYml("{}");
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
        Path yml = writeYml("{}");
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
        Path yml = writeYml("{budgetChars: 20}");
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
}
