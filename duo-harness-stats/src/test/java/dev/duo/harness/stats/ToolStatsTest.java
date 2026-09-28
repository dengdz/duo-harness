package dev.duo.harness.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ToolStats 计数器单测：计数、排序、报表与 JSON 形态、并发写安全。 */
class ToolStatsTest {

    @Test
    void recordAccumulatesTotalsAndFailures() {
        ToolStats stats = new ToolStats();
        stats.record("read", false);
        stats.record("read", false);
        stats.record("read", true);
        stats.record("bash", true);

        assertEquals(3, stats.total("read"));
        assertEquals(1, stats.failed("read"));
        assertEquals(1, stats.total("bash"));
        assertEquals(1, stats.failed("bash"));
        assertEquals(0, stats.total("missing"));
        assertEquals(0, stats.failed("missing"));
    }

    @Test
    void emptyTableShowsGuidance() {
        assertTrue(new ToolStats().toTable().contains("暂无工具执行记录"));
    }

    @Test
    void tableListsToolsByVolumeDesc() {
        ToolStats stats = new ToolStats();
        stats.record("read", false);
        stats.record("read", false);
        stats.record("bash", false);

        String table = stats.toTable();
        assertTrue(table.indexOf("read") < table.indexOf("bash"), "总量降序: \n" + table);
        assertTrue(table.contains("read: 2 次（失败 0）"));
        assertTrue(table.contains("bash: 1 次（失败 0）"));
    }

    @Test
    void sameVolumeKeepsNameOrder() {
        ToolStats stats = new ToolStats();
        stats.record("bash", false);
        stats.record("read", false);

        assertEquals("bash", stats.toolsByVolume().get(0));
        assertEquals("read", stats.toolsByVolume().get(1));
    }

    @Test
    void jsonShapeCarriesUsageEntries() throws Exception {
        ToolStats stats = new ToolStats();
        stats.record("read", false);
        stats.record("read", true);
        stats.record("bash", false);

        JsonNode json = new ObjectMapper().readTree(stats.toJson());
        assertEquals(2, json.get("usage").size());
        assertEquals("read", json.get("usage").get(0).get("tool").asText());
        assertEquals(2, json.get("usage").get(0).get("total").asLong());
        assertEquals(1, json.get("usage").get(0).get("failed").asLong());
        assertEquals("bash", json.get("usage").get(1).get("tool").asText());
    }

    @Test
    void concurrentRecordsAreAllCounted() throws Exception {
        ToolStats stats = new ToolStats();
        int threads = 8;
        int rounds = 500;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < rounds; i++) {
                        stats.record("read", i % 10 == 0);
                    }
                });
            }
        } // try-with-resources 关闭即等待全部任务完成

        assertEquals(threads * rounds, stats.total("read"));
        assertEquals(threads * rounds / 10, stats.failed("read"));
    }
}
