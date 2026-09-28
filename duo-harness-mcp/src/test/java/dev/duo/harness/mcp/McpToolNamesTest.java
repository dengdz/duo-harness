package dev.duo.harness.mcp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 套件：McpToolNamesTest —— 公开命名 + 稳定匹配（C2 工单 15）：去哈希精确匹配
 * 取代消费方的前缀匹配——前缀碰撞负例（write_file 与 write_file_x 互为前缀，
 * 旧 startsWith 形态必误伤）、清洗形态、非公开名形态（3 用例）。
 */
class McpToolNamesTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：McpToolNamesTest —— 稳定匹配：去哈希精确匹配、前缀碰撞负例、"
                + "清洗形态、非公开名形态（3 用例） ===");
    }

    @Test
    void matchesExactToolAfterStrippingHash() {
        String publicName = McpToolNames.publicName("files", "write_file");
        assertTrue(McpToolNames.matches(publicName, "files", "write_file"),
                "公开名与自身（server + 原始名）精确匹配");
        assertFalse(McpToolNames.matches(publicName, "files", "read_file"),
                "同 server 不同工具不匹配");
        assertFalse(McpToolNames.matches(publicName, "other", "write_file"),
                "同工具名不同 server 不匹配");
    }

    @Test
    void prefixCollisionDoesNotOverreach() {
        // C2 工单 15 前缀碰撞负例：write_file 与 write_file_x 互为前缀——
        // 旧 startsWith("mcp__files__write_file") 形态对两者都命中（误伤）
        String write = McpToolNames.publicName("files", "write_file");
        String writeX = McpToolNames.publicName("files", "write_file_x");
        assertFalse(McpToolNames.matches(writeX, "files", "write_file"),
                "write_file_x 不被 write_file 的声明误伤（前缀碰撞回归锁）");
        assertTrue(McpToolNames.matches(writeX, "files", "write_file_x"),
                "write_file_x 与自身声明精确匹配");
        assertFalse(McpToolNames.matches(write, "files", "write_file_x"),
                "反向同理");
    }

    @Test
    void rawNameCleaningAndNonPublicNames() {
        // 原始名含非法字符：匹配按清洗后展示形态比较（与 publicName 同口径）
        String cleaned = McpToolNames.publicName("files", "fs.write");
        assertTrue(McpToolNames.matches(cleaned, "files", "fs.write"),
                "清洗形态（点号→下划线）经原始名匹配");
        // 非公开名形态（本地工具名）：不匹配任何 MCP 声明
        assertFalse(McpToolNames.matches("bash", "files", "write_file"),
                "本地工具名不匹配");
        // 缺哈希段的裸形态按展示形态宽松匹配——公开名恒带哈希段（publicName 生成
        // 必带），裸形态实际不可达，宽松无害且更鲁棒
        assertTrue(McpToolNames.matches("mcp__files__write_file", "files", "write_file"),
                "裸形态按展示形态匹配（宽松，实际不可达）");
        assertFalse(McpToolNames.matches(null, "files", "write_file"), "null 不匹配");
    }
}
