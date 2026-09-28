package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.tools.PipelineTimeout;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 套件：ToolTimeoutsTest —— 等待超时单点（C2 工单 09）：clamp 边界（缺省/低值/
 * 上限/非正/非数值）、管线放宽余量，以及 task-output 补套管线放宽后的结构断言
 * ——长等待请求的管线超时必须超过管线缺省（此前漏套，120s 腰斩 600s 承诺）（3 用例）。
 */
class ToolTimeoutsTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ToolTimeoutsTest —— 超时单点：clamp 边界、管线余量、"
                + "task-output 管线放宽结构断言（3 用例） ===");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode args(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void clampBoundaries() {
        assertEquals(120_000, ToolTimeouts.clamp(args("{\"timeoutMs\":0}"), 120_000),
                "非正数按缺省");
        assertEquals(300, ToolTimeouts.clamp(args("{\"timeoutMs\":300}"), 120_000), "低值透传");
        assertEquals(600_000, ToolTimeouts.clamp(args("{\"timeoutMs\":900000}"), 120_000),
                "超上限取上限");
        assertEquals(30_000, ToolTimeouts.clamp(args("{}"), 30_000), "缺席按调用方缺省");
        assertEquals(30_000, ToolTimeouts.clamp(args("{\"timeoutMs\":\"快\"}"), 30_000),
                "非数值按缺省");
    }

    @Test
    void pipelineTimeoutAddsHeadroom() {
        assertEquals(305_000, ToolTimeouts.pipelineTimeout(args("{\"timeoutMs\":300000}"), 30_000),
                "等待 + 5s 余量");
        assertEquals(605_000, ToolTimeouts.pipelineTimeout(args("{\"timeoutMs\":999999}"), 30_000),
                "clamp 后再加余量（不超 605s）");
    }

    @Test
    void taskOutputPipelineRelaxesBeyondDefaultForLongWaits() {
        // C2 工单 09 核心断言：task-output 长等待请求的管线超时必须超过管线缺省
        // （120s）——此前该工具不覆写 pipelineTimeoutMs，默认部署下模型按契约传
        // timeoutMs=600000 的等待必被管线以通用超时错误腰斩（bash 已套用、task
        // 工具族漏套的同型排查缺口）
        TaskOutputTool tool = new TaskOutputTool(null);
        long relaxed = tool.pipelineTimeoutMs(args("{\"taskId\":\"bg-1\",\"timeoutMs\":600000}"));
        assertTrue(relaxed > PipelineTimeout.DEFAULT_TIMEOUT_MS,
                "长等待的管线放宽必须超过管线缺省 " + PipelineTimeout.DEFAULT_TIMEOUT_MS
                        + "，实际 " + relaxed);
        assertEquals(605_000, relaxed, "600s 等待 + 5s 余量");
    }
}
