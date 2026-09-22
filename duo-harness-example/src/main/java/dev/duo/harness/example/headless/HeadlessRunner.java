package dev.duo.harness.example.headless;

import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.AgentReply;
import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.agent.governance.ContextGovernance;
import dev.duo.harness.agent.presenter.PresenterAssembly;
import dev.duo.harness.agent.prompt.PromptRegistry;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolsService;

import java.io.PrintStream;
import java.util.LinkedHashMap;

/**
 * headless 一次性任务运行器（M23 工单 07，ADR-0025）：装配 agent（复用
 * {@link PresenterAssembly} 装配链）→ stdout 逐行 NDJSON 事件流 → 退出码即成败
 * 契约（completed→0 否则 1）。诊断信息只走 stderr，stdout 只留事件流。
 *
 * <p>投影双源配对（{@link Projector}）：commit-point 词汇以会话事件流为唯一投影源
 * （{@code session.addListener}，只推订阅后的新事件——恢复会话天然不重放历史）；
 * tool_result 的 {@code status} 取 AgentListener 的 isError（事件层无失败标志），
 * callId 取订阅侧暂存的 tool/result 事件——两源在 agent 执行线程上同步成对到达，
 * 配对确定。</p>
 */
public final class HeadlessRunner {

    /** 装配所需服务束（Boot 树集成段经视图取供给；测试直供替身）。 */
    public record Services(Context root, LlmAdapter llm, ToolsService tools,
                           PromptRegistry prompts, InteractionService answers,
                           Session session, PrintStream out, PrintStream err) {
    }

    private HeadlessRunner() {
    }

    /**
     * 跑一个任务并投影 NDJSON：session 开场帧（先写后订阅）→ 事件帧 → status
     * turn_end → error 帧（仅未完成）→ final 帧（无损，必发——消费者解析锚点）。
     *
     * @return 进程退出码：completed→0，迭代上限/中断/异常→1
     */
    public static int run(Services s, String prompt, int maxIterations) {
        PrintStream out = s.out();
        Projector projector = new Projector(out);
        out.println(NdjsonFrames.sessionFrame(s.session().id(), System.getProperty("user.dir")));
        s.session().addListener((index, event) -> projector.onSessionEvent(event));
        try {
            ContextGovernance governance = PresenterAssembly.governance(s.llm());
            // 管线超时兜底（与 CLI/Web 呈现位同款缺省 120s）：卡死的工具不得拖死流程
            // ——否则"永不静默挂死"只在交互轴成立，被 SIGTERM 杀掉的任务还会被消费者记成功
            PresenterAssembly.mountPipelineTimeout(s.root(), s.tools(),
                    PresenterAssembly.parsePipelineTimeoutMs(null));
            PresenterAssembly.registerInteractionTools(s.root(), s.tools(), s.answers(),
                    HeadlessAnswerer.PRESENTER_ID, () -> s.session(), () -> { });
            // 禁交互回答者：error 帧经投影器落 stdout（保持 NDJSON 单一流）；
            // 包审计装饰器——审批/计划 deny 落会话 approval/* 留痕（resume 后历史可见）
            s.answers().register(s.root(), new dev.duo.harness.agent.AuditingAnswerer(
                    () -> s.session(), new HeadlessAnswerer(
                            message -> out.println(NdjsonFrames.frame("error",
                                    NdjsonFrames.fields("message", message))))));
            ChatAgent agent = PresenterAssembly.chatAgent(s.llm(), s.tools(), s.session(),
                    s.prompts(), maxIterations, governance, HeadlessAnswerer.PRESENTER_ID);
            AgentReply reply = agent.send(prompt, projector.listener());
            var endFields = NdjsonFrames.fields("phase", "turn_end");
            if (projector.lastUsage() != null) {
                // usage 缺样本宁缺勿假（DSH 纪律）：无实测样本整项省略
                endFields.put("usage", projector.lastUsage());
            }
            out.println(NdjsonFrames.frame("status", endFields));
            if (!reply.completed()) {
                out.println(NdjsonFrames.frame("error", NdjsonFrames.fields("message", reply.finalText())));
            }
            out.println(NdjsonFrames.finalFrame(reply.finalText()));
            return reply.completed() ? 0 : 1;
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.toString();
            s.err().println("[headless] 任务执行失败: " + e);
            out.println(NdjsonFrames.frame("error",
                    NdjsonFrames.fields("message", "任务执行失败: " + reason)));
            out.println(NdjsonFrames.finalFrame("任务执行失败: " + reason));
            return 1;
        }
    }

    /**
     * 投影器：会话事件 → NDJSON 帧；AgentListener 补 tool_result 的 status。
     * 非线程安全——全部回调都发生在 agent 执行线程（Session.append 的监听器
     * 同步回调 + agent 同步回调），串行有序。
     */
    private static final class Projector implements AgentListener {

        private final PrintStream out;
        /** 最近一条 tool/result 事件（onToolResult 回调紧随其 append，配对取 callId）。 */
        private SessionEvent lastToolResult;
        /** 最近一次提交的 token 用量（assistant/message 事件携带；缺样本 null）。 */
        private dev.duo.harness.session.TokenUsage lastUsage;

        Projector(PrintStream out) {
            this.out = out;
        }

        /** 会话事件 → 帧（commit-point 词汇；assistantChunk 等过程细节不投影）。 */
        void onSessionEvent(SessionEvent event) {
            switch (event.type()) {
                case SessionEvent.USER_MESSAGE -> out.println(NdjsonFrames.frame("status",
                        NdjsonFrames.fields("phase", "turn_start")));
                case SessionEvent.TOOL_CALL -> {
                    LinkedHashMap<String, Object> fields = NdjsonFrames.fields("callId", event.toolCallId());
                    fields.put("tool", event.toolName());
                    fields.put("input", event.text());
                    if (event.reasoning() != null && !event.reasoning().isBlank()) {
                        fields.put("reasoning", event.reasoning());
                    }
                    out.println(NdjsonFrames.frame("tool_call", fields));
                }
                case SessionEvent.TOOL_RESULT -> lastToolResult = event;
                case SessionEvent.ASSISTANT_MESSAGE -> {
                    lastUsage = event.usage();
                    out.println(NdjsonFrames.frame("text",
                            NdjsonFrames.fields("text", event.text())));
                }
                case SessionEvent.ASSISTANT_INTERRUPTED -> {
                    LinkedHashMap<String, Object> fields = NdjsonFrames.fields("text", event.text());
                    fields.put("interrupted", true);
                    out.println(NdjsonFrames.frame("text", fields));
                }
                default -> { /* 审计/回放类事件不进 headless 词汇（thinking 有源才发） */ }
            }
        }

        /** tool_result 帧：callId 来自订阅侧暂存事件、status 来自 isError（配对完成）。 */
        @Override public void onToolResult(String toolName, String resultText, boolean isError) {
            String callId = lastToolResult != null ? lastToolResult.toolCallId() : null;
            LinkedHashMap<String, Object> fields = NdjsonFrames.fields("callId", callId);
            fields.put("tool", toolName);
            fields.put("status", isError ? "error" : "completed");
            fields.put(isError ? "error" : "result", resultText);
            out.println(NdjsonFrames.frame("tool_result", fields));
        }

        AgentListener listener() {
            return this;
        }

        dev.duo.harness.session.TokenUsage lastUsage() {
            return lastUsage;
        }
    }

}
