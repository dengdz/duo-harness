package dev.duo.harness.example.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.internal.ToolCallingAgent;
import dev.duo.harness.agent.presenter.PresenterAssembly;
import dev.duo.harness.agent.subagent.SubagentHost;
import dev.duo.harness.agent.subagent.SubagentManager;
import dev.duo.harness.agent.subagent.SubagentPlugin;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.llm.ToolCallRequest;
import dev.duo.harness.session.Message;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * subagent 功能演示入口：按**生产装配路径**驱动全程——呈现位发布宿主构件
 * （{@link PresenterAssembly#publishSubagentHost}）、subagent 插件按模板装配五件
 * 工具、父 agent 经 spawn/fork 派生子代理。脚本化 LLM（无外部调用）按 system 提示
 * 分流父/子：子代理连续多轮调工具后收尾，演示其与主 agent 同构的循环能力；
 * 产出留在 {@code target/duo-subagent-demo-sessions/}，可打开看事件溯源 JSONL。
 *
 * <p>运行：{@code mvn -pl duo-harness-example -am compile exec:java
 * -Dexec.mainClass=dev.duo.harness.example.subagent.SubagentDemoMain}</p>
 */
public final class SubagentDemoMain {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 模板专属提示——父子 LLM 分流的判据（子代理 system 即模板提示，不继承父提示）。 */
    private static final String CHILD_MARKER = "你是调研助手";

    private SubagentDemoMain() {
    }

    public static void main(String[] args) throws Exception {
        Path sessionsDir = Path.of("target", "duo-subagent-demo-sessions");
        deleteRecursive(sessionsDir);
        Files.createDirectories(sessionsDir);

        System.out.println("=== duo-harness M15 subagent 功能演示（脚本化 LLM，无外部调用） ===\n");

        Context root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        interface ToolsView { ToolsService tools(); }
        ToolsService tools = root.as(ToolsView.class).tools();
        tools.register(root, echoDef());

        // 模板装配（等价于 yml 的 subagent.templates 段）：工具配置权在部署者
        JsonNode config = JSON.readTree("""
                {"templates": [{"name": "researcher", "tools": ["echo"],
                                "prompt": "你是调研助手，只做调研不改文件"}]}""");

        Session parent = Session.create(sessionsDir);
        // 呈现位职责：发布宿主构件（LLM / 治理阈值 / 当前会话）——子代理执行链的父侧来源
        LlmAdapter llm = demoLlm();
        PresenterAssembly.publishSubagentHost(root, llm, null, () -> parent);
        // subagent 插件：模板非空 → 装配五件工具（含 spawn/fork）并发布 manager
        root.plugin(new SubagentPlugin(), config).awaitStartup();
        SubagentManager manager = root.as(SubagentsView.class).subagents();

        System.out.println("[装配] 模板 researcher（工具 [echo] + 专属提示）");
        System.out.println("[装配] 宿主构件已发布（presenter 服务）；工具域现有: " + tools.list().stream()
                .map(ToolDefinition::name).toList() + "\n");

        // ===== 第一段：spawn（全新子代理，多轮工具循环） =====
        System.out.println("─── spawn：全新子代理 ───");
        new ToolCallingAgent(llm, tools, parent, "你是主控 agent", 5)
                .send("帮我调研一下 X 的可行性", demoListener());

        Set<SessionEvent> seen = new HashSet<>();
        SessionEvent completed = awaitCompleted(parent, seen, 1);
        Path spawnChild = sessionsDir.resolve(SubagentManager.SUBDIRECTORY).resolve(completed.toolCallId() + ".jsonl");
        awaitReleased(spawnChild);
        System.out.println("[回流] 父会话收到 subagent/completed（id=" + completed.toolCallId() + "）：");
        System.out.println("    " + completed.text().replace("\n", "\n    "));

        List<Message> projected = parent.deriveMessages();
        Message last = projected.get(projected.size() - 1);
        System.out.println("[投影] 父 LLM 下一轮上下文最后一条（" + last.role() + " 角色）：");
        System.out.println("    " + last.content().replace("\n", "\n    "));
        System.out.println("[子会话] 事件链（子代理自身的多轮循环全程落档）：");
        printChildChain(spawnChild, false);
        System.out.println("[锁] 子会话已完成，锁已释放=" + !Session.isOccupied(spawnChild)
                + "（可打开回放子代理全程）");
        System.out.println("[侧栏] 会话列表 = " + Session.list(sessionsDir).stream()
                .map(Session.SessionSummary::id).toList() + " —— 子会话在 subagents/ 下，不进侧栏\n");

        // ===== 第二段：fork（播种上一段积累的父背景） =====
        System.out.println("─── fork：带着父对话背景派生 ───");
        new ToolCallingAgent(llm, tools, parent, "你是主控 agent", 5)
                .send("fork 一个去深挖方案细节", demoListener());

        SessionEvent forkCompleted = awaitCompleted(parent, seen, 2);
        Path forkChild = sessionsDir.resolve(SubagentManager.SUBDIRECTORY).resolve(forkCompleted.toolCallId() + ".jsonl");
        awaitReleased(forkChild);
        System.out.println("[播种] 子会话开头段（父平衡完成轮前缀原样落子日志）：");
        printChildChain(forkChild, true);

        System.out.println("[状态] list_agents 视角：" + manager.all().size() + " 个子代理，状态 "
                + manager.all().stream().map(e -> e.state().toString()).toList());
        System.out.println("\n[文件] 会话目录：" + sessionsDir.toAbsolutePath());
        System.out.println("       父会话在顶层、子会话在 subagents/ 下——可打开 JSONL 看事件溯源原文");
        parent.close();
        root.dispose();
    }

    /** 打印子会话事件链（含种子边界标注）；prefixOnly = 只打到种子边界（fork 播种段）。 */
    private static void printChildChain(Path childJsonl, boolean prefixOnly) {
        Session child = Session.load(childJsonl);
        List<SessionEvent> events = child.events();
        for (int i = 0; i < events.size(); i++) {
            SessionEvent event = events.get(i);
            if (SessionEvent.SUBAGENT_SEED_BOUNDARY.equals(event.type())) {
                System.out.println("  [" + i + "] 「种子边界」" + event.text());
                if (prefixOnly) {
                    break;
                }
                continue;
            }
            System.out.println("  [" + i + "] " + event.type() + "：" + abbreviate(event.text()));
        }
        child.close();
    }

    // ===== 脚本化 LLM（按 system 提示分流父/子） =====

    /**
     * 父子共用 adapter：子代理 system 即模板专属提示（判据）。父按调用次序在第 1 轮
     * 走 spawn、第 3 轮走 fork，其余直答；子代理每段三轮循环（两轮工具 + 收尾直答）。
     */
    private static LlmAdapter demoLlm() {
        AtomicInteger parentCalls = new AtomicInteger();
        AtomicInteger childRounds = new AtomicInteger();
        return new LlmAdapter() {
            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                if (request.systemPrompt().contains(CHILD_MARKER)) {
                    int round = childRounds.incrementAndGet() % 3;
                    if (round == 1) {
                        System.out.println("  [子代理] 第 1 轮：调工具探路");
                        return new LlmTurn("", List.of(new ToolCallRequest("c1", "echo",
                                "{\"text\":\"第一步探索\"}")));
                    }
                    if (round == 2) {
                        System.out.println("  [子代理] 第 2 轮：看到结果，继续调工具深挖"
                                + "（循环未结束——与主 agent 同一套迭代机制）");
                        return new LlmTurn("", List.of(new ToolCallRequest("c2", "echo",
                                "{\"text\":\"第二步深挖\"}")));
                    }
                    System.out.println("  [子代理] 第 3 轮：信息足够，给出最终回答收尾循环");
                    String answer = "调研结论：X 可行，建议按方案 A 推进。";
                    textSink.accept(answer);
                    return new LlmTurn(answer, List.of());
                }
                int call = parentCalls.incrementAndGet();
                if (call == 1 || call == 3) {
                    String tool = call == 1 ? "spawn" : "fork";
                    System.out.println("[父 LLM] 发起工具调用 " + tool + "(template=researcher, task=…)");
                    return new LlmTurn("", List.of(new ToolCallRequest("p" + call, tool,
                            "{\"template\":\"researcher\",\"task\":\"调研 X 的可行性\"}")));
                }
                String answer = "已派子代理在后台执行，稍等它的结论。";
                System.out.println("[父 LLM] 直答：" + answer);
                textSink.accept(answer);
                return new LlmTurn(answer, List.of());
            }

            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                throw new UnsupportedOperationException("agent 循环走 streamTurn");
            }
        };
    }

    /** 过程叙述监听：派生工具的调用与结果逐条可见。 */
    private static AgentListener demoListener() {
        return new AgentListener() {
            @Override
            public void onToolCall(String toolName, String argumentsJson) {
                System.out.println("  -> [调工具] " + toolName + " " + abbreviate(argumentsJson)
                        + "（立即返回，不阻塞）");
            }

            @Override
            public void onToolResult(String toolName, String resultText, boolean isError) {
                System.out.println("  <- [结果] " + resultText);
            }
        };
    }

    /** 子代理可用工具：回显输入（模板可用集成员）。 */
    private static ToolDefinition echoDef() {
        return new ToolDefinition() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "回显输入";
            }

            @Override
            public JsonNode parameters() {
                try {
                    return JSON.readTree("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public Object execute(ToolExecution execution) {
                String text = execution.args().path("text").asText();
                System.out.println("  [子代理] 调用 echo(text=" + text + ")");
                return "echo:" + text;
            }
        };
    }

    // ===== 辅助 =====

    /** subagents 服务的视图接口（方法名即服务名）。 */
    interface SubagentsView {

        SubagentManager subagents();
    }

    /** 轮询等待父会话第 {@code ordinal} 个 completed 事件。 */
    private static SessionEvent awaitCompleted(Session parent, Set<SessionEvent> seen, int ordinal)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (SessionEvent event : parent.events()) {
                if (SessionEvent.SUBAGENT_COMPLETED.equals(event.type()) && !seen.contains(event)) {
                    seen.add(event);
                    return event;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待第 " + ordinal + " 个子代理完成超时");
    }

    /** 轮询等待子会话锁释放（close 在回流之后）。 */
    private static void awaitReleased(Path childJsonl) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (!Session.isOccupied(childJsonl)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待子会话锁释放超时");
    }

    private static String abbreviate(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }

    /** 递归删目录（演示目录每次重置）。 */
    private static void deleteRecursive(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                    // 演示目录清理，失败不阻塞
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}
