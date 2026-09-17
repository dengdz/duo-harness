package dev.duo.harness.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发调度用例（ADR-0018，工单 M17-01）：model 序成对提交、墙钟并行收益、
 * 屏障互斥、审批即独占、fail-closed 判定、并发度=1 串行退化、并发轮与串行轮
 * 日志同构对照。探针工具记录执行区间（nanoTime），区间重叠即真并发、
 * 与独占调用不重叠即屏障生效。
 */
class ToolCallingAgentConcurrencyTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ToolCallingAgentConcurrencyTest —— 并发调度：model 序成对提交、屏障互斥、fail-closed（7 用例） ===");
    }

    @TempDir
    Path tempDir;

    /** 执行区间台账：屏障互斥与真并发的判定数据源。 */
    static final class Journal {
        record Interval(String tool, long enter, long exit) {
        }

        final List<Interval> intervals = new CopyOnWriteArrayList<>();

        Interval of(String tool) {
            return intervals.stream().filter(i -> i.tool.equals(tool)).findFirst()
                    .orElseThrow(() -> new AssertionError("工具未执行: " + tool));
        }

        static boolean overlaps(Interval a, Interval b) {
            return a.enter() < b.exit() && b.enter() < a.exit();
        }
    }

    /** 受控探针工具：可配延迟与并发安全形态，执行区间记入 Journal。 */
    private static final class ProbeTool implements ToolDefinition {
        enum Safety { SAFE, UNSAFE, THROWS, BY_ARGS }

        private final String name;
        private final long delayMs;
        private final Safety safety;
        private final Journal journal;
        private final boolean requiresApproval;

        ProbeTool(String name, long delayMs, Safety safety, Journal journal) {
            this(name, delayMs, safety, journal, false);
        }

        ProbeTool(String name, long delayMs, Safety safety, Journal journal, boolean requiresApproval) {
            this.name = name;
            this.delayMs = delayMs;
            this.safety = safety;
            this.journal = journal;
            this.requiresApproval = requiresApproval;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "探针工具 " + name; }

        @Override public JsonNode parameters() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("type", "object");
        }

        @Override public boolean requiresApproval() { return requiresApproval; }

        @Override public boolean isConcurrencySafe(JsonNode args) {
            return switch (safety) {
                case SAFE -> true;
                case UNSAFE -> false;
                case THROWS -> throw new IllegalStateException("判定故障探针");
                case BY_ARGS -> args.path("safe").asBoolean(false);
            };
        }

        @Override public String execute(ToolExecution exec) {
            long enter = System.nanoTime();
            sleepUnchecked(delayMs);
            long exit = System.nanoTime();
            journal.intervals.add(new Journal.Interval(name, enter, exit));
            return "ok:" + name;
        }
    }

    /** 真 ToolsService + 探针注册（工具域三段管线照常生效，审批 fail-closed 真实路径）。 */
    private ToolsService toolsWith(ProbeTool... probes) {
        return toolsWith(dev.duo.harness.core.api.Context.root(), probes);
    }

    /** 同上，复用调用方给的 root（需在同一 root 上再挂审批等治理插件时用）。 */
    private ToolsService toolsWith(dev.duo.harness.core.api.Context toolsRoot, ProbeTool... probes) {
        toolsRoot.plugin(new dev.duo.harness.tools.ToolsPlugin(), null).awaitStartup();
        ToolsService impl = toolsRoot.as(ToolsView.class).tools();
        for (ProbeTool probe : probes) {
            impl.register(toolsRoot, probe);
        }
        return impl;
    }

    /** 两轮脚本：第 1 轮返回给定 tool_calls，第 2 轮直答收尾。 */
    private static LlmAdapter twoTurnAdapter(List<ToolCallRequest> firstRoundCalls) {
        AtomicInteger round = new AtomicInteger();
        return new LlmAdapter() {
            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                if (round.incrementAndGet() == 1) {
                    return new LlmTurn("", firstRoundCalls, null);
                }
                textSink.accept("done");
                return new LlmTurn("done", List.of());
            }

            @Override
            public void stream(ChatRequest request,
                               java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                throw new UnsupportedOperationException("循环路径走 streamTurn");
            }
        };
    }

    private Session newSession() throws IOException {
        return Session.create(tempDir.resolve("sessions"));
    }

    private static List<String> typeSequence(Session session) {
        return session.events().stream().map(SessionEvent::type).toList();
    }

    @Test
    void parallelGroupCommitsInModelOrderAndBeatsSerialWallClock() throws IOException {
        Journal journal = new Journal();
        ToolsService tools = toolsWith(
                new ProbeTool("slow", 300, ProbeTool.Safety.SAFE, journal),
                new ProbeTool("fast1", 100, ProbeTool.Safety.SAFE, journal),
                new ProbeTool("fast2", 100, ProbeTool.Safety.SAFE, journal));
        Session session = newSession();
        // model 序故意让最慢的排最前：成对有序提交下 fast 们完成也要等 slow 先提交
        ToolCallingAgent agent = new ToolCallingAgent(twoTurnAdapter(List.of(
                new ToolCallRequest("c1", "slow", "{}"),
                new ToolCallRequest("c2", "fast1", "{}"),
                new ToolCallRequest("c3", "fast2", "{}"))),
                tools, session, "你是助手", 10);

        long start = System.nanoTime();
        AgentReply reply = agent.send("并行读", AgentListener.NONE);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("done", reply.finalText());
        // 事件日志：成对且严格按 model 序（slow 先提交，尽管它最慢）
        assertEquals(List.of(
                SessionEvent.USER_MESSAGE,
                SessionEvent.TOOL_CALL, SessionEvent.TOOL_RESULT,
                SessionEvent.TOOL_CALL, SessionEvent.TOOL_RESULT,
                SessionEvent.TOOL_CALL, SessionEvent.TOOL_RESULT,
                SessionEvent.ASSISTANT_MESSAGE), typeSequence(session));
        assertEquals("slow", session.events().get(1).toolName());
        assertEquals("fast1", session.events().get(3).toolName());
        assertEquals("fast2", session.events().get(5).toolName());
        // 墙钟：三工具并行，总耗时接近最慢者（300ms）而非串行和（500ms）
        assertTrue(elapsedMs < 480, "并行墙钟应显著小于串行和 500ms，实测 " + elapsedMs + "ms");
    }

    @Test
    void concurrentRoundLogShapeMatchesSerial() throws IOException {
        // 同构对照：同一脚本分别以并发与并发度=1（串行）跑，事件日志逐条同型同序
        // ——M10 游标回放 / M13 尾窗 / 投影 / 崩溃闭合零改动的验证根基
        List<ToolCallRequest> calls = List.of(
                new ToolCallRequest("c1", "alpha", "{}"),
                new ToolCallRequest("c2", "beta", "{}"),
                new ToolCallRequest("c3", "gamma", "{}"));
        Journal parallelJournal = new Journal();
        Session parallelSession = newSession();
        ToolCallingAgent parallel = new ToolCallingAgent(twoTurnAdapter(calls),
                toolsWith(
                        new ProbeTool("alpha", 60, ProbeTool.Safety.SAFE, parallelJournal),
                        new ProbeTool("beta", 60, ProbeTool.Safety.SAFE, parallelJournal),
                        new ProbeTool("gamma", 60, ProbeTool.Safety.SAFE, parallelJournal)),
                parallelSession, "你是助手", 10);
        Journal serialJournal = new Journal();
        Session serialSession = newSession();
        ToolCallingAgent serial = new ToolCallingAgent(twoTurnAdapter(calls),
                toolsWith(
                        new ProbeTool("alpha", 1, ProbeTool.Safety.SAFE, serialJournal),
                        new ProbeTool("beta", 1, ProbeTool.Safety.SAFE, serialJournal),
                        new ProbeTool("gamma", 1, ProbeTool.Safety.SAFE, serialJournal)),
                serialSession, new dev.duo.harness.agent.prompt.PromptRegistry("你是助手"), 10, 1, null);

        parallel.send("问", AgentListener.NONE);
        serial.send("问", AgentListener.NONE);

        assertEquals(serialSession.events().size(), parallelSession.events().size(), "事件数一致");
        for (int k = 0; k < serialSession.events().size(); k++) {
            SessionEvent expect = serialSession.events().get(k);
            SessionEvent actual = parallelSession.events().get(k);
            assertEquals(expect.type(), actual.type(), "第 " + k + " 条事件类型一致");
            assertEquals(expect.toolName(), actual.toolName(), "第 " + k + " 条事件工具一致");
            assertEquals(expect.text(), actual.text(), "第 " + k + " 条事件文本一致");
        }
    }

    @Test
    void exclusiveCallIsBarrierWhileSafeCallsOverlap() throws IOException {
        Journal journal = new Journal();
        ToolsService tools = toolsWith(
                new ProbeTool("safeA", 150, ProbeTool.Safety.SAFE, journal),
                new ProbeTool("safeB", 150, ProbeTool.Safety.SAFE, journal),
                new ProbeTool("exclusive", 1, ProbeTool.Safety.UNSAFE, journal),
                new ProbeTool("safeC", 1, ProbeTool.Safety.SAFE, journal));
        Session session = newSession();
        ToolCallingAgent agent = new ToolCallingAgent(twoTurnAdapter(List.of(
                new ToolCallRequest("c1", "safeA", "{}"),
                new ToolCallRequest("c2", "safeB", "{}"),
                new ToolCallRequest("c3", "exclusive", "{}"),
                new ToolCallRequest("c4", "safeC", "{}"))),
                tools, session, "你是助手", 10);

        agent.send("混发轮", AgentListener.NONE);

        Journal.Interval a = journal.of("safeA");
        Journal.Interval b = journal.of("safeB");
        Journal.Interval exclusive = journal.of("exclusive");
        Journal.Interval c = journal.of("safeC");
        assertTrue(Journal.overlaps(a, b), "连续安全调用应真并发（区间重叠）");
        assertFalse(Journal.overlaps(exclusive, a), "独占调用是屏障：与安全组不重叠");
        assertFalse(Journal.overlaps(exclusive, b), "独占调用是屏障：与安全组不重叠");
        assertFalse(Journal.overlaps(exclusive, c), "独占调用是屏障：与后继组不重叠");
        // 日志序：安全组（A、B）→ 独占（exclusive）→ 后继组（C），全部按 model 序成对
        assertEquals(List.of(
                SessionEvent.USER_MESSAGE,
                SessionEvent.TOOL_CALL, SessionEvent.TOOL_RESULT,
                SessionEvent.TOOL_CALL, SessionEvent.TOOL_RESULT,
                SessionEvent.TOOL_CALL, SessionEvent.TOOL_RESULT,
                SessionEvent.TOOL_CALL, SessionEvent.TOOL_RESULT,
                SessionEvent.ASSISTANT_MESSAGE), typeSequence(session));
        assertEquals("exclusive", session.events().get(5).toolName());
        assertEquals("safeC", session.events().get(7).toolName());
    }

    @Test
    void approvalRequiredNeverEntersPoolEvenWhenDeclaredSafe() throws IOException {
        Journal journal = new Journal();
        // auto-approve 白名单放行 approved——审批通过、本体真实执行，独占性可由区间判定
        // （无策略时拒绝发生在本体之前，探针不执行，"审批即独占"只能在放行路径观察）
        dev.duo.harness.core.api.Context root = dev.duo.harness.core.api.Context.root();
        var approvalConfig = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        approvalConfig.put("policy", "auto-approve");
        approvalConfig.putArray("allowedTools").add("approved");
        root.plugin(new dev.duo.harness.tools.ApprovalPlugin(), approvalConfig).awaitStartup();
        ToolsService tools = toolsWith(root,
                new ProbeTool("approved", 50, ProbeTool.Safety.SAFE, journal, true),
                new ProbeTool("plain", 50, ProbeTool.Safety.SAFE, journal));
        Session session = newSession();
        ToolCallingAgent agent = new ToolCallingAgent(twoTurnAdapter(List.of(
                new ToolCallRequest("c1", "approved", "{}"),
                new ToolCallRequest("c2", "plain", "{}"))),
                tools, session, "你是助手", 10);

        AgentReply reply = agent.send("审批轮", AgentListener.NONE);

        assertFalse(Journal.overlaps(journal.of("approved"), journal.of("plain")),
                "需审批调用无论安全声明如何一律独占（审批即独占）");
        assertEquals("done", reply.finalText(), "循环继续");
        assertFalse(reply.toolInvocations().stream()
                        .filter(i -> i.tool().equals("approved")).findFirst().orElseThrow().isError(),
                "白名单放行：审批通过且本体执行（区间已记录）");
    }

    @Test
    void maxParallelOneRestoresSerialExecution() throws IOException {
        Journal journal = new Journal();
        ToolsService tools = toolsWith(
                new ProbeTool("one", 80, ProbeTool.Safety.SAFE, journal),
                new ProbeTool("two", 80, ProbeTool.Safety.SAFE, journal),
                new ProbeTool("three", 80, ProbeTool.Safety.SAFE, journal));
        Session session = newSession();
        ToolCallingAgent agent = new ToolCallingAgent(twoTurnAdapter(List.of(
                new ToolCallRequest("c1", "one", "{}"),
                new ToolCallRequest("c2", "two", "{}"),
                new ToolCallRequest("c3", "three", "{}"))),
                tools, session, new dev.duo.harness.agent.prompt.PromptRegistry("你是助手"), 10, 1, null);

        long start = System.nanoTime();
        agent.send("串行排障", AgentListener.NONE);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= 235, "并发度=1 即完全串行（墙钟 >= 240ms 串行和），实测 " + elapsedMs + "ms");
        assertFalse(Journal.overlaps(journal.of("one"), journal.of("two")), "串行退化：区间两两不重叠");
        assertFalse(Journal.overlaps(journal.of("two"), journal.of("three")), "串行退化：区间两两不重叠");
    }

    @Test
    void judgmentFailureFallsBackToExclusive() throws IOException {
        Journal journal = new Journal();
        ToolsService tools = toolsWith(
                new ProbeTool("throws", 50, ProbeTool.Safety.THROWS, journal),
                new ProbeTool("steady", 50, ProbeTool.Safety.SAFE, journal));
        Session session = newSession();
        ToolCallingAgent agent = new ToolCallingAgent(twoTurnAdapter(List.of(
                new ToolCallRequest("c1", "throws", "{}"),
                new ToolCallRequest("c2", "steady", "{}"))),
                tools, session, "你是助手", 10);

        agent.send("判定故障轮", AgentListener.NONE);

        assertFalse(Journal.overlaps(journal.of("throws"), journal.of("steady")),
                "判定抛错按独占处理（fail-closed）");
        assertEquals("done",
                session.events().get(session.events().size() - 1).text(),
                "循环不受判定故障影响");
    }

    @Test
    void perArgsJudgmentDrivesPooling() throws IOException {
        // 按参判定：safe=true 并行、safe=false 独占（带参声明的语义验证）
        Journal bothSafeJournal = new Journal();
        Session bothSafeSession = newSession();
        ToolCallingAgent bothSafe = new ToolCallingAgent(twoTurnAdapter(List.of(
                new ToolCallRequest("c1", "byargs", "{\"safe\":true}"),
                new ToolCallRequest("c2", "steady", "{}"))),
                toolsWith(
                        new ProbeTool("byargs", 100, ProbeTool.Safety.BY_ARGS, bothSafeJournal),
                        new ProbeTool("steady", 100, ProbeTool.Safety.SAFE, bothSafeJournal)),
                bothSafeSession, "你是助手", 10);
        bothSafe.send("参数安全轮", AgentListener.NONE);
        assertTrue(Journal.overlaps(bothSafeJournal.of("byargs"), bothSafeJournal.of("steady")),
                "safe=true 的调用进并行池（区间重叠）");

        Journal mixedJournal = new Journal();
        Session mixedSession = newSession();
        ToolCallingAgent mixed = new ToolCallingAgent(twoTurnAdapter(List.of(
                new ToolCallRequest("c1", "byargs", "{\"safe\":false}"),
                new ToolCallRequest("c2", "steady", "{}"))),
                toolsWith(
                        new ProbeTool("byargs", 100, ProbeTool.Safety.BY_ARGS, mixedJournal),
                        new ProbeTool("steady", 100, ProbeTool.Safety.SAFE, mixedJournal)),
                mixedSession, "你是助手", 10);
        mixed.send("参数不安全轮", AgentListener.NONE);
        assertFalse(Journal.overlaps(mixedJournal.of("byargs"), mixedJournal.of("steady")),
                "safe=false 的调用独占（区间不重叠）");
    }

    private static void sleepUnchecked(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
