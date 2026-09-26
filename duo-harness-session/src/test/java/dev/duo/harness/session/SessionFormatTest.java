package dev.duo.harness.session;

import dev.duo.harness.core.api.PluginException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话文件格式版本用例（M26 工单 01，ADR-0028 决策二）：版本头序列化/解析往返、
 * 头行判定容错、迁移链相邻接力（v1→v2→v3）、缺环与跳级拒绝。
 */
class SessionFormatTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SessionFormatTest —— 版本头往返（含 cwd/省略 cwd）、头行判定（事件行/坏行 false）、迁移链相邻接力与缺环拒绝、非相邻迁移器拒绝（8 用例） ===");
    }

    /** 测试用迁移器：每步给行尾追加标记（验证接力顺序与中间态）。 */
    private record MarkedMigration(int fromVersion, int toVersion) implements SessionFormat.LineMigration {

        @Override
        public List<String> migrate(List<String> eventLines) {
            List<String> out = new ArrayList<>(eventLines.size());
            for (String line : eventLines) {
                out.add(line + "|m" + fromVersion() + toVersion());
            }
            return out;
        }
    }

    @Test
    void headerLineRoundTripsWithCwd() {
        Path cwd = Path.of("/tmp/proj");
        String line = SessionFormat.headerLine(SessionFormat.CURRENT_VERSION, cwd);
        assertTrue(line.contains("\"type\":\"session\""), line);
        assertTrue(line.contains("\"version\":" + SessionFormat.CURRENT_VERSION), line);

        SessionFormat.Header header = SessionFormat.parseHeader(line);
        assertEquals(SessionFormat.CURRENT_VERSION, header.version());
        assertEquals(cwd, header.cwd());
    }

    @Test
    void headerLineOmitsCwdWhenNull() {
        String line = SessionFormat.headerLine(1, null);
        assertFalse(line.contains("cwd"), "null cwd 应省略字段: " + line);
        assertNull(SessionFormat.parseHeader(line).cwd());
    }

    @Test
    void isHeaderLineDistinguishesHeaderEventAndGarbage() {
        assertTrue(SessionFormat.isHeaderLine("{\"type\":\"session\",\"version\":1}"));
        assertFalse(SessionFormat.isHeaderLine("{\"type\":\"user/message\",\"at\":1,\"text\":\"hi\"}"));
        assertFalse(SessionFormat.isHeaderLine("不是 JSON"));
    }

    @Test
    void migrateChainsAdjacentStepsInOrder() {
        List<String> lines = List.of("{\"type\":\"user/message\",\"at\":1,\"text\":\"a\"}");
        List<String> migrated = SessionFormat.migrate(1, lines,
                List.of(new MarkedMigration(1, 2), new MarkedMigration(2, 3)), 3);
        // 逐级接力：v1→v2 先于 v2→v3，标记顺序可验证
        assertEquals(List.of("{\"type\":\"user/message\",\"at\":1,\"text\":\"a\"}|m12|m23"), migrated);
    }

    @Test
    void migrateFailsOnMissingStep() {
        PluginException e = assertThrows(PluginException.class,
                () -> SessionFormat.migrate(1, List.of(),
                        List.of(new MarkedMigration(1, 2)), 3));
        assertTrue(e.getMessage().contains("链断裂"), e.getMessage());
    }

    @Test
    void migrateFailsOnWrongDirection() {
        assertThrows(PluginException.class,
                () -> SessionFormat.migrate(3, List.of(), List.of(), 3));
    }

    @Test
    void migrateRejectsNonAdjacentMigrationAtRuntime() {
        // from=1 直跳 to=3 的迁移器：即使注册也在执行期被相邻性校验拒绝
        PluginException e = assertThrows(PluginException.class,
                () -> SessionFormat.migrate(1, List.of("x"),
                        List.of(new MarkedMigration(1, 3)), 3));
        assertTrue(e.getMessage().contains("相邻"), e.getMessage());
    }

    @Test
    void requireValidMigrationRejectsNonAdjacent() {
        assertThrows(IllegalArgumentException.class,
                () -> SessionFormat.requireValidMigration(1, 3));
        assertThrows(IllegalArgumentException.class,
                () -> SessionFormat.requireValidMigration(2, 2));
        SessionFormat.requireValidMigration(2, 3); // 相邻升一版合法
    }
}
