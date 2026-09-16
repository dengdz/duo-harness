package dev.duo.harness.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 80K 事件性能基准（ADR-0014 决策 3）：读侧零拷贝与单趟窗口的达标锚——
 * **默认跳过**（全量套件零成本），手动启用跑数字：
 * {@code mvn -pl duo-harness-session test -Dtest=SessionPerfBenchmarkTest -Dperf.benchmark=true}
 *
 * <p>事件形态贴近真实会话：user/assistant 对话 + 每 10 轮一对 tool_call/result，
 * 79,200 事件经 JSONL 直写 + {@link Session#load} 构造（绕过逐条落盘的写放大，
 * 生成成本不计入读侧计时）。达标线（ADR-0014 决策 4）：单次"分页定窗 + 投影回放"
 * 总投影成本 ≤ 15ms。grill 基线（2026-09-16，优化前）：events() 0.13ms /
 * deriveMessages() 8.78ms / tailWindow(50) 8.31ms。</p>
 */
class SessionPerfBenchmarkTest {

    /** 36,000 轮对话 + 3,600 对工具事件 = 79,200 事件（≈80K）。 */
    private static final int ROUNDS = 36_000;
    private static final int EXPECTED_EVENTS = ROUNDS * 2 + (ROUNDS / 10) * 2;

    /** 达标线：单次"定窗 + 投影回放"（ADR-0014 决策 4）。 */
    private static final long BUDGET_MS = 15;

    @TempDir
    Path dir;

    @Test
    @EnabledIfSystemProperty(named = "perf.benchmark", matches = "true")
    void snapshot80kProjectionWithinBudget() throws Exception {
        Session session = bigSession();
        try {
            assertEquals(EXPECTED_EVENTS, session.events().size());

            // 达标口径：分页定窗（tailWindow）+ 投影回放（deriveMessages）一次往返
            warmup(session);
            int rounds = 20;
            long windowNanos = 0;
            long deriveNanos = 0;
            for (int r = 0; r < rounds; r++) {
                long a = System.nanoTime();
                var window = session.tailWindow(50);
                long b = System.nanoTime();
                var messages = session.deriveMessages();
                long c = System.nanoTime();
                windowNanos += b - a;
                deriveNanos += c - b;
                assertTrue(window.startEvent() >= 0);
                // 全事件均投影：user/assistant 为消息，tool_call/result 为 Function Calling 形态消息
                assertEquals(EXPECTED_EVENTS, messages.size());
            }
            double totalMs = (windowNanos + deriveNanos) / 1e6 / rounds;
            System.out.printf("80K 事件（%d 轮均值）：tailWindow(50)=%.2fms deriveMessages()=%.2fms 定窗+投影=%.2fms（达标线 %dms）%n",
                    rounds, windowNanos / 1e6 / rounds, deriveNanos / 1e6 / rounds, totalMs, BUDGET_MS);
            assertTrue(totalMs <= BUDGET_MS, "80K 定窗+投影应 ≤ " + BUDGET_MS + "ms，实测 " + totalMs + "ms");
        } finally {
            session.close(); // 释放文件锁通道（load 持锁至关闭）
        }
    }

    /** 预热 JIT（3 轮不计入），消除首跑解释执行噪声。 */
    private void warmup(Session session) {
        for (int i = 0; i < 3; i++) {
            session.tailWindow(50);
            session.deriveMessages();
        }
    }

    /** 事件日志直写 JSONL 后 load——生成路径与读侧计时解耦。 */
    private Session bigSession() throws Exception {
        Path jsonl = dir.resolve("bench-80k").resolve("bench.jsonl");
        Files.createDirectories(jsonl.getParent());
        var JSON = new com.fasterxml.jackson.databind.ObjectMapper();
        StringBuilder sb = new StringBuilder(1 << 24);
        for (int i = 0; i < ROUNDS; i++) {
            sb.append(JSON.writeValueAsString(SessionEvent.userMessage("消息" + i + "：" + "x".repeat(80)))).append('\n');
            sb.append(JSON.writeValueAsString(SessionEvent.assistantMessage("回复" + i + "：" + "y".repeat(160)))).append('\n');
            if (i % 10 == 0) {
                sb.append(JSON.writeValueAsString(SessionEvent.toolCall("call-" + i, "read", "{\"path\":\"a.txt\"}"))).append('\n');
                sb.append(JSON.writeValueAsString(SessionEvent.toolResult("call-" + i, "read", "内容" + i))).append('\n');
            }
        }
        Files.writeString(jsonl, sb.toString());
        return Session.load(jsonl);
    }
}
