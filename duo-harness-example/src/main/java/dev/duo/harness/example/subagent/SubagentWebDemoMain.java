package dev.duo.harness.example.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import dev.duo.harness.web.WebFace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 子任务卡视觉验证入口（M15 工单 05，红线 5）：预置三种状态的父会话事件
 * （运行中 / 完成 / 中断）与一个已完成子会话的 JSONL，启动 Web 面供浏览器
 * 实测——卡片三态、结果概要折叠、"查看子任务全程"回放弹层。
 *
 * <p>运行：{@code mvn -pl duo-harness-example -am compile exec:java
 * -Dexec.mainClass=dev.duo.harness.example.subagent.SubagentWebDemoMain}
 * 然后浏览器打开 {@code http://127.0.0.1:18080}</p>
 */
public final class SubagentWebDemoMain {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 预置子代理 id（与 Session id 同形态，过 WebFace 白名单）。 */
    private static final String DONE_ID = "20260916-090000-0a01";
    private static final String RUNNING_ID = "20260916-090000-0a02";
    private static final String ABORTED_ID = "20260916-090000-0a03";

    public static void main(String[] args) throws Exception {
        Path sessionsDir = Path.of("target", "duo-subagent-web-sessions");
        deleteRecursive(sessionsDir);
        Files.createDirectories(sessionsDir);

        // 父会话：两轮对话 + 三张子任务卡的引用事件（SSE 尾部快照整窗回放给页面）
        Session parent = Session.create(sessionsDir);
        parent.append(SessionEvent.userMessage("帮我调研一下 X 的可行性"));
        parent.append(SessionEvent.toolCall("c1", "spawn",
                "{\"template\":\"researcher\",\"task\":\"调研 X 的可行性\"}"));
        parent.append(SessionEvent.subagentSpawned(DONE_ID, "researcher",
                "{\"task\":\"调研 X 的可行性\",\"mode\":\"spawn\",\"parentSessionId\":\"" + parent.id() + "\"}"));
        parent.append(SessionEvent.toolResult("c1", "spawn",
                "{\"agentId\":\"" + DONE_ID + "\",\"status\":\"started\"}"));
        parent.append(SessionEvent.assistantMessage("已派子代理在后台执行，稍等它的结论。"));
        parent.append(SessionEvent.subagentCompleted(DONE_ID,
                "子代理 " + DONE_ID + "（模板 researcher）已完成。\n最终回答：\n调研结论：X 可行，建议按方案 A 推进。"));

        parent.append(SessionEvent.toolCall("c2", "spawn",
                "{\"template\":\"worker\",\"task\":\"批量整理资料\"}"));
        parent.append(SessionEvent.subagentSpawned(RUNNING_ID, "worker",
                "{\"task\":\"批量整理资料\",\"mode\":\"spawn\",\"parentSessionId\":\"" + parent.id() + "\"}"));
        parent.append(SessionEvent.toolResult("c2", "spawn",
                "{\"agentId\":\"" + RUNNING_ID + "\",\"status\":\"started\"}"));

        parent.append(SessionEvent.toolCall("c3", "fork",
                "{\"template\":\"worker\",\"task\":\"深挖一个方向\"}"));
        parent.append(SessionEvent.subagentSpawned(ABORTED_ID, "worker",
                "{\"task\":\"深挖一个方向\",\"mode\":\"fork\",\"parentSessionId\":\"" + parent.id() + "\"}"));
        parent.append(SessionEvent.toolResult("c3", "fork",
                "{\"agentId\":\"" + ABORTED_ID + "\",\"status\":\"started\"}"));
        parent.append(SessionEvent.subagentCompleted(ABORTED_ID,
                "子代理 " + ABORTED_ID + "（模板 worker）已被中止：未产出结果。"));

        // 完成态子会话的 JSONL（含 fork 播种背景段 + 多轮工具循环——抽屉回放的数据源）
        Path subagentsDir = sessionsDir.resolve("subagents");
        Files.createDirectories(subagentsDir);
        List<String> lines = List.of(
                line("user/message", "我们已定方案 A"),
                line("assistant/message", "好的，按方案 A 执行"),
                line("subagent/seed-boundary", "前 2 条来自父会话 " + parent.id()),
                line("user/message", "调研 X 的可行性"),
                "{\"type\":\"tool/call\",\"at\":5,\"text\":\"{\\\"text\\\":\\\"第一步探索\\\"}\","
                        + "\"toolCallId\":\"c9\",\"toolName\":\"echo\"}",
                "{\"type\":\"tool/result\",\"at\":6,\"text\":\"echo:第一步探索\",\"toolCallId\":\"c9\",\"toolName\":\"echo\"}",
                "{\"type\":\"tool/call\",\"at\":7,\"text\":\"{\\\"text\\\":\\\"第二步深挖\\\"}\","
                        + "\"toolCallId\":\"c10\",\"toolName\":\"echo\"}",
                "{\"type\":\"tool/result\",\"at\":8,\"text\":\"echo:第二步深挖\",\"toolCallId\":\"c10\",\"toolName\":\"echo\"}",
                line("assistant/message", "调研结论：X 可行，建议按方案 A 推进。"),
                line("subagent/completed", "子代理 " + DONE_ID + " 已完成"));
        Files.write(subagentsDir.resolve(DONE_ID + ".jsonl"), lines);

        // Web 面装配（测试同款最小集；mock agent 直答——视觉验证不调真 LLM）
        Context root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        interface WebToolsView { ToolsService tools(); }
        ToolsService tools = root.as(WebToolsView.class).tools();
        ChatAgent agent = (userText, listener) -> {
            listener.onChunk("演示模式：预置会话已含三张子任务卡。");
            return new AgentReply("演示模式", List.of(), true);
        };
        WebFace face = WebFace.start(18080, root, tools, parent, agent, null, null, sessionsDir);
        System.out.println("=== 子任务卡视觉验证面已启动: http://127.0.0.1:" + face.port() + " ===");
        System.out.println("预置三张卡：完成（" + DONE_ID + "）/ 运行中（" + RUNNING_ID
                + "）/ 已中断（" + ABORTED_ID + "）；完成态卡可点『查看子任务全程』");
        Thread.currentThread().join(); // 常驻供浏览器实测；Ctrl-C 退出
    }

    /** 简单事件行（type/at/text 三字段）。 */
    private static String line(String type, String text) {
        return "{\"type\":\"" + type + "\",\"at\":1,\"text\":\"" + text.replace("\"", "\\\"") + "\"}";
    }

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
