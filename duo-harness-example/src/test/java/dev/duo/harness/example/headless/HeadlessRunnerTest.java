package dev.duo.harness.example.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.InteractionPlugin;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * headless --json 用例（M23 工单 07，ADR-0025）：NDJSON 七类词汇投影与帧序、
 * 退出码契约（completed→0 否则 1）、流内禁交互（自动 deny + 显式 error 帧）、
 * --session-id 恢复续跑事件流连续、bounding 降级链、入口参数解析、装配 yml
 * 呈现位行预过滤。假 LLM + 真 ToolsService 管线（AgentReplMainTest 同款 seam）。
 */
class HeadlessRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：HeadlessRunnerTest —— headless --json：NDJSON 投影/退出码/"
                + "禁交互/会话恢复/bounding/参数/yml 预过滤（7 用例） ===");
    }

    @Test
    @Timeout(30)
    void framesSequenceAndExitCodeZero(@TempDir Path sessionsDir) throws Exception {
        // 全链：user → echo 工具 → 直答——帧序锁定为
        // session → status(turn_start) → tool_call → tool_result → text → status(turn_end) → final
        Harness harness = harness(sessionsDir, twoTurnScript(List.of(
                new ToolCallRequest("call_1", "echo", "{\"text\":\"你好\"}"))));
        int code = HeadlessRunner.run(harness.services, "打个招呼", 10);
        assertEquals(0, code, "completed→0");

        assertEquals(List.of("session", "status", "tool_call", "tool_result", "text",
                "status", "final"), harness.types(), "帧序列");
        assertEquals(harness.sessionId(), harness.frame("session").get("sessionId").asText(),
                "session 帧 id");
        assertEquals("turn_start", harness.frames.get(1).get("phase").asText(), "开场相位");
        assertEquals("call_1", harness.frames.get(2).get("callId").asText(), "tool_call 关联键");
        assertEquals("echo", harness.frames.get(2).get("tool").asText(), "工具名");
        assertEquals("completed", harness.frames.get(3).get("status").asText(), "成功 status");
        assertEquals("echo:你好", harness.frames.get(3).get("result").asText(), "结果载荷");
        assertNull(harness.frames.get(3).get("error"), "成功帧无 error 字段");
        assertTrue(harness.frames.get(4).get("text").asText().contains("工具说"),
                "text 帧为提交点全文");
        assertNotNull(harness.frame("final").get("text"), "final 帧承载答案");
        harness.close();
    }

    @Test
    @Timeout(30)
    void interactionAutoDeniedWithExplicitErrorFrame(@TempDir Path sessionsDir) throws Exception {
        // 流内禁交互：模型调 ask_user → headless 回答者自动 deny → error 帧显式呈现
        // → 模型收尾 final——流程不挂死、退出码 0（拒绝是正常结果流）
        Harness harness = harness(sessionsDir, twoTurnScript(List.of(
                new ToolCallRequest("call_1", "ask_user", "{\"question\":\"确认？\"}"))));
        int code = HeadlessRunner.run(harness.services, "问我一件事", 10);
        assertEquals(0, code, "deny 后流程照常收尾");
        assertTrue(harness.types().contains("error"), "显式 error 帧: " + harness.types());
        assertTrue(harness.frame("error").get("message").asText().contains("禁交互"),
                "error 帧点名禁交互");
        assertTrue(harness.types().contains("tool_result"), "拒绝作为工具结果回给模型");
        assertEquals("error", harness.frames.stream()
                        .filter(f -> "tool_result".equals(f.path("type").asText()))
                        .findFirst().orElseThrow().get("status").asText(),
                "ask_user 被拒后工具结果为 error 形态");
        harness.close();
    }

    @Test
    @Timeout(30)
    void approvalAutoDeniedForDeclaredTool(@TempDir Path sessionsDir) throws Exception {
        // 审批路径：requiresApproval 声明的工具 → ask 请求 → headless 回答者自动 deny
        // → error 帧 + 工具错误结果回给模型 → 流程收尾不挂死（交互轴全覆盖）
        Harness harness = harness(sessionsDir, twoTurnScript(List.of(
                new ToolCallRequest("call_1", "guarded", "{}"))));
        harness.services.tools().register(harness.services.root(), new ToolDefinition() {
            @Override public String name() { return "guarded"; }

            @Override public String description() { return "需审批的受控工具"; }

            @Override public JsonNode parameters() {
                return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode().put("type", "object");
            }

            @Override public boolean requiresApproval() { return true; }

            @Override public Object execute(ToolExecution execution) {
                return "不应执行到这";
            }
        });
        int code = HeadlessRunner.run(harness.services, "试试受控工具", 10);
        assertEquals(0, code, "deny 后流程照常收尾");
        assertTrue(harness.types().contains("error"), "显式 error 帧: " + harness.types());
        assertEquals("error", harness.frames.stream()
                        .filter(f -> "tool_result".equals(f.path("type").asText()))
                        .findFirst().orElseThrow().get("status").asText(),
                "审批被拒后工具结果为 error 形态");
        harness.close();
    }

    @Test
    @Timeout(30)
    void exitCodeOneWhenIterationCapHit(@TempDir Path sessionsDir) throws Exception {
        // 永远要求调工具 → 迭代上限（2）→ 未完成 → error 帧 + final 帧（锚点必达）+ 退出 1
        Harness harness = harness(sessionsDir, alwaysToolScript());
        int code = HeadlessRunner.run(harness.services, "死循环任务", 2);
        assertEquals(1, code, "非 completed→1");
        assertTrue(harness.types().contains("error"), "未完成发 error 帧: " + harness.types());
        assertEquals("final", harness.types().get(harness.types().size() - 1), "final 必发兜底");
        assertTrue(harness.frame("error").get("message").asText().contains("迭代"),
                "error 帧说明上限终止");
        harness.close();
    }

    @Test
    @Timeout(30)
    void sessionResumeContinuesEventStream(@TempDir Path sessionsDir) throws Exception {
        // --session-id 恢复：首轮落会话 → load 续跑——事件流连续（不重放历史 text）
        Harness first = harness(sessionsDir, directAnswerScript("第一轮答案"));
        assertEquals(0, HeadlessRunner.run(first.services, "第一问", 10));
        first.close();

        Session resumed = Session.load(sessionsDir.resolve(first.sessionId() + ".jsonl"));
        Harness second = harness(sessionsDir, directAnswerScript("第二轮答案"), resumed);
        assertEquals(0, HeadlessRunner.run(second.services, "第二问", 10));
        assertEquals(first.sessionId(), second.sessionId(), "恢复同一会话 id");
        List<JsonNode> texts = second.framesOf("text");
        assertEquals(1, texts.size(), "事件流连续：历史不重放，只有本轮 text");
        assertEquals("第二轮答案", texts.get(0).get("text").asText());
        second.close();
    }

    @Test
    void boundingDegradesOversizedFrames() {
        // 单字符串 8K 截断 + truncated 标记；行级 32K 降级 {type,truncated}；final 豁免
        JsonNode mid = read(NdjsonFrames.frame("text",
                NdjsonFrames.fields("text", "x".repeat(9_000))));
        assertTrue(mid.get("text").asText().length() <= NdjsonFrames.MAX_STRING_CHARS,
                "字符串值截到 8K 内");
        assertTrue(mid.get("truncated").asBoolean(), "截断标记在场");

        // 行级降级：单字段已先截到 8K，需多字段合计撑破 32K 行预算（降级链递进）
        LinkedHashMap<String, Object> multi = NdjsonFrames.fields("a", "y".repeat(9_000));
        multi.put("b", "z".repeat(9_000));
        multi.put("c", "w".repeat(9_000));
        multi.put("d", "v".repeat(9_000));
        JsonNode huge = read(NdjsonFrames.frame("text", multi));
        assertNull(huge.get("a"), "多字段合计超限整体降级丢载荷");
        assertNull(huge.get("b"), "载荷键 b 缺席");
        assertNull(huge.get("c"), "载荷键 c 缺席");
        assertNull(huge.get("d"), "载荷键 d 缺席");

        // 负例：正常帧不截断、无标记、不降级
        JsonNode normal = read(NdjsonFrames.frame("text",
                NdjsonFrames.fields("text", "正常长度")));
        assertFalse(normal.has("truncated"), "正常帧无截断标记");
        assertEquals("正常长度", normal.get("text").asText(), "正常帧载荷原样");
        JsonNode nearLimit = read(NdjsonFrames.frame("text",
                NdjsonFrames.fields("text", "n".repeat(8_000))));
        assertEquals(8_000, nearLimit.get("text").asText().length(), "8K 内不截断");
        assertFalse(nearLimit.has("truncated"), "临界内无标记");
        assertTrue(huge.get("truncated").asBoolean(), "降级帧带截断标记");
        assertEquals("text", huge.get("type").asText(), "降级帧保留类型");

        JsonNode finalNode = read(NdjsonFrames.finalFrame("z".repeat(50_000)));
        assertEquals(50_000, finalNode.get("text").asText().length(), "final 帧无损豁免");
        assertNull(finalNode.get("truncated"), "final 帧无截断标记");
    }

    @Test
    void parseArguments(@TempDir Path tempDir) throws Exception {
        Path yml = tempDir.resolve("custom.yml");
        Files.writeString(yml, "plugins: []\n");
        HeadlessArgs full = HeadlessArgs.parse(
                new String[]{"--json", "--session-id", "s1", yml.toString(), "跑", "个", "任务"});
        assertTrue(full.headless());
        assertEquals("s1", full.sessionId());
        assertEquals(yml, full.yml(), "首段 .yml 文件识别为装配路径");
        assertEquals("跑 个 任务", full.prompt(), "余词拼空格为任务文本");
        assertTrue(full.errors().isEmpty());

        HeadlessArgs bare = HeadlessArgs.parse(new String[]{"--json"});
        assertTrue(bare.headless());
        assertFalse(bare.errors().isEmpty(), "无任务文本即 usage error");
        assertNull(bare.prompt());

        HeadlessArgs missingValue = HeadlessArgs.parse(new String[]{"--json", "--session-id"});
        assertFalse(missingValue.errors().isEmpty(), "--session-id 缺值即 usage error");

        HeadlessArgs legacy = HeadlessArgs.parse(new String[]{});
        assertFalse(legacy.headless(), "无 --json 保持常驻现状");
        assertTrue(legacy.errors().isEmpty());
    }

    @Test
    void filteredCopyDisablesPresenterRows(@TempDir Path tempDir) throws Exception {
        // 呈现位行（cli/web）预过滤为 disabled，工具域行原样保留
        Path yml = tempDir.resolve("agent.yml");
        Files.writeString(yml, """
                plugins:
                  - id: tools
                    name: dev.duo.harness.tools.ToolsPlugin
                  - id: cli
                    name: dev.duo.harness.cli.CliPlugin
                    config:
                      maxIterations: 30
                  - id: web
                    name: dev.duo.harness.web.WebPlugin
                    config:
                      port: 18080
                """);
        Path copy = HeadlessBoot.filteredCopy(yml);
        ObjectMapper yaml = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        JsonNode rows = yaml.readTree(Files.readString(copy)).get("plugins");
        assertEquals(3, rows.size(), "行数保留（disabled 语义即行在场实例不在）");
        assertNull(rows.get(0).get("disabled"), "工具域行不受影响");
        assertTrue(rows.get(1).get("disabled").asBoolean(), "cli 行禁用");
        assertTrue(rows.get(2).get("disabled").asBoolean(), "web 行禁用");
    }

    // ===== 测试装配 =====

    /** 运行容器：捕获 stdout 的 NDJSON 流，解析成帧供断言。 */
    private static final class Harness {

        final HeadlessRunner.Services services;
        final ByteArrayOutputStream buffer;
        final Session session;
        final List<JsonNode> frames = new ArrayList<>();

        Harness(HeadlessRunner.Services services, ByteArrayOutputStream buffer, Session session) {
            this.services = services;
            this.buffer = buffer;
            this.session = session;
        }

        List<String> types() {
            return frames().stream().map(f -> f.get("type").asText()).toList();
        }

        /** 解析捕获的 stdout（run 结束后调用一次并缓存）。 */
        List<JsonNode> frames() {
            if (frames.isEmpty()) {
                for (String line : buffer.toString(StandardCharsets.UTF_8).split("\n")) {
                    if (!line.isBlank()) {
                        try {
                            frames.add(MAPPER.readTree(line));
                        } catch (Exception e) {
                            throw new IllegalStateException("非 JSON 行: " + line, e);
                        }
                    }
                }
            }
            return frames;
        }

        JsonNode frame(String type) {
            return frames().stream().filter(f -> type.equals(f.path("type").asText()))
                    .findFirst().orElseThrow(() -> new AssertionError("缺帧: " + type));
        }

        List<JsonNode> framesOf(String type) {
            return frames().stream().filter(f -> type.equals(f.path("type").asText())).toList();
        }

        String sessionId() {
            return session.id();
        }

        void close() {
            session.close();
        }
    }

    private Harness harness(Path sessionsDir, LlmAdapter llm) {
        return harness(sessionsDir, llm, null);
    }

    private Harness harness(Path sessionsDir, LlmAdapter llm, Session reuse) {
        Context root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        root.plugin(new InteractionPlugin(), null).awaitStartup();
        // 审批闸（无档位全 ask，InteractiveApprovalPlugin）：requiresApproval 声明的
        // 工具经 ask 请求交回答者瀑布——HeadlessAnswerer 的审批路径由此打通
        root.plugin(new dev.duo.harness.tools.InteractiveApprovalPlugin(), null).awaitStartup();
        ToolsService tools = root.as(HeadlessBoot.HeadlessToolsView.class).tools();
        tools.register(root, echoDef());
        InteractionService answers = root.as(HeadlessBoot.HeadlessAnswersView.class).answers();
        Session session = reuse != null ? reuse : Session.create(sessionsDir);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        return new Harness(new HeadlessRunner.Services(root, llm, tools,
                new dev.duo.harness.agent.prompt.PromptRegistry("你是测试助手"),
                answers, session, out, System.err), buffer, session);
    }

    private static ToolDefinition echoDef() {
        return new ToolDefinition() {
            @Override public String name() { return "echo"; }

            @Override public String description() { return "回声工具"; }

            @Override public JsonNode parameters() {
                return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode().put("type", "object");
            }

            @Override public Object execute(ToolExecution execution) {
                return "echo:" + execution.args().path("text").asText("");
            }
        };
    }

    /** 两轮脚本：首轮要求调用给定工具，次轮读工具结果直答（对齐真实消费语义）。 */
    private static LlmAdapter twoTurnScript(List<ToolCallRequest> firstTurnCalls) {
        return new LlmAdapter() {
            int calls = 0;

            @Override public LlmTurn streamTurn(ChatRequest request,
                                                java.util.function.Consumer<String> textSink) {
                calls++;
                if (calls == 1) {
                    return new LlmTurn("", firstTurnCalls);
                }
                String last = request.messages().get(request.messages().size() - 1).content();
                String answer = "工具说: " + last;
                textSink.accept(answer);
                return new LlmTurn(answer, List.of());
            }

            @Override public void stream(ChatRequest request,
                                         java.util.function.Consumer<ChatChunk> onChunk) {
                throw new UnsupportedOperationException("agent 循环走 streamTurn");
            }
        };
    }

    private static LlmAdapter directAnswerScript(String answer) {
        return new LlmAdapter() {
            @Override public LlmTurn streamTurn(ChatRequest request,
                                                java.util.function.Consumer<String> textSink) {
                textSink.accept(answer);
                return new LlmTurn(answer, List.of());
            }

            @Override public void stream(ChatRequest request,
                                         java.util.function.Consumer<ChatChunk> onChunk) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static LlmAdapter alwaysToolScript() {
        return new LlmAdapter() {
            int seq = 0;

            @Override public LlmTurn streamTurn(ChatRequest request,
                                                java.util.function.Consumer<String> textSink) {
                seq++;
                return new LlmTurn("", List.of(new ToolCallRequest("call_" + seq, "echo",
                        "{\"text\":\"第" + seq + "轮\"}")));
            }

            @Override public void stream(ChatRequest request,
                                         java.util.function.Consumer<ChatChunk> onChunk) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static JsonNode read(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
