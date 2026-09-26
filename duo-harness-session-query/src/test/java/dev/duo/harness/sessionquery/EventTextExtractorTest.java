package dev.duo.harness.sessionquery;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 索引白名单用例（ADR-0022 决策 8 + M26-04）：入/不入两面的行为钉子。
 */
class EventTextExtractorTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：EventTextExtractorTest —— 索引白名单：消息/工具/todo/交付声明入，chunk/reasoning/治理事件不入（含 M26-04 交付声明用例） ===");
    }

    @Test
    void deliverablePresentedPathsIndexed() {
        // M26-04：交付声明入索引白名单——路径数组逐条拼接（按成果文件名反查会话）
        String paths = "[\"/tmp/proj/out/总结报告-final.md\", \"/tmp/proj/data.json\"]";
        String searchable = EventTextExtractor.searchableText(
                new IndexedEvent("deliverable/presented", 1, paths, null));
        assertTrue(searchable.contains("总结报告-final.md"), searchable);
        assertTrue(searchable.contains("data.json"), searchable);
        // 坏形态回退原文（透明往返纪律）
        assertEquals("坏形态原文", EventTextExtractor.searchableText(
                new IndexedEvent("deliverable/presented", 2, "坏形态原文", null)));
    }

    @Test
    void reasoningNeverInPayload() {
        assertNull(EventTextExtractor.searchableText(
                new IndexedEvent("title", 1, "标题词", null)), "title 不入索引");
        assertNull(EventTextExtractor.searchableText(
                new IndexedEvent("assistant/chunk", 2, "流式词", null)), "chunk 不入索引");
    }
}
