package dev.duo.harness.cli;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import dev.duo.harness.agent.prompt.PromptPlugin;
import dev.duo.harness.agent.skills.SkillsPlugin;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.InteractionPlugin;
import dev.duo.harness.tools.ToolsPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CliPlugin REPL seam 用例（M11-02）：注入脚本输入/输出/mock LLM 驱动终端循环——
 * 会话事件序列、/new 换绑落新会话、/exit 的 idle 语义（会话锁释放、回答者摘除）。
 * 装配经真实插件树（tools + prompts + answers + skills + cli）。
 */
class CliPluginTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：CliPluginTest —— CLI 呈现位插件：事件驱动 REPL（busy 插队/应答闸门/EOF 不腰斩）、"
                + "命令注册表入口（/exit 审计、/new 换绑、未知清单、/plan 进出与续接、/permission 档位、/compact 压缩点）、"
                + "/stop 协作式中断与续接、/exit idle 锁释放、占用提示、工具叙述行通用形态、子任务过程行、"
                + "/compact 压缩、/permission 持久化、/title 改名（19 用例） ===");
    }

    interface ToolsView {

        dev.duo.harness.tools.ToolsService tools();
    }

    interface AnswersView {

        dev.duo.harness.tools.InteractionService answers();
    }

    @TempDir
    Path tempDir;

    /** 脚本输入行：text + 可选等待标记（null = 默认等第 N 个 idle 提示符「你>」）。 */
    private record InputLine(String text, String awaitMarker) {

        static InputLine of(String text) {
            return new InputLine(text, null);
        }

        /** busy 期投递：等输出出现标记（如「[调工具]」「[待审批]」）才写行。 */
        static InputLine paced(String text, String marker) {
            return new InputLine(text, marker);
        }
    }

    /** 脚本化 REPL 夹具：节奏化注入输入/mock LLM/临时会话目录，跑完脚本等 idle。 */
    private static final class Fixture {
        final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        final Context root = Context.root();
        final Path sessionsDir;
        private final List<InputLine> lines;
        private volatile String feederError;

        Fixture(Path sessionsDir, String scriptedInput, LlmAdapter llm) throws Exception {
            this(sessionsDir, splitLines(scriptedInput), llm, null);
        }

        Fixture(Path sessionsDir, List<InputLine> lines, LlmAdapter llm) throws Exception {
            this(sessionsDir, lines, llm, null);
        }

        /**
         * 带前置装配钩子的重载（M15 子任务用例）：钩子在 CliPlugin 启动前执行——
         * 呈现位装配（registerSubagentTools）在 CliPlugin.apply 内探测服务，故
         * SubagentPlugin 等前置件必须先挂好。
         */
        Fixture(Path sessionsDir, String scriptedInput, LlmAdapter llm,
                java.util.function.Consumer<Context> preAssembly) throws Exception {
            this(sessionsDir, splitLines(scriptedInput), llm, preAssembly);
        }

        Fixture(Path sessionsDir, List<InputLine> lines, LlmAdapter llm,
                java.util.function.Consumer<Context> preAssembly) throws Exception {
            this.sessionsDir = sessionsDir;
            this.lines = lines;
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            root.plugin(new PromptPlugin(), JsonNodeFactory.instance.objectNode()
                    .put("systemPrompt", "测试提示")).awaitStartup();
            root.plugin(new InteractionPlugin(), null).awaitStartup();
            root.plugin(new SkillsPlugin(), JsonNodeFactory.instance.objectNode()
                    .putArray("disabled")).awaitStartup();
            // 命令注册表（M19）：CliPlugin 硬依赖 "commands"——四命令迁移后的注册目标
            root.plugin(new dev.duo.harness.agent.commands.CommandsPlugin(),
                    JsonNodeFactory.instance.objectNode()).awaitStartup();
            // fs 工具族先挂：提供 workspace 服务——WorkspaceApprovalPlugin 与 CliPlugin
            // 都依赖它，编程挂载缺依赖会永久挂起（awaitStartup 无超时）；workspace 根锚定测试临时目录
            root.plugin(new dev.duo.harness.tools.fs.FsToolsPlugin(),
                    JsonNodeFactory.instance.objectNode()
                            .put("mode", "read-only")
                            .put("root", sessionsDir.toAbsolutePath().getParent().toString()))
                    .awaitStartup();
            // 档位审批：ask 落回答者（本 fixture 为终端 y/n）；依赖 workspace（上一步已挂）
            root.plugin(new dev.duo.harness.tools.fs.WorkspaceApprovalPlugin(), null).awaitStartup();
            registerGuardedWriteTool();
            if (preAssembly != null) {
                preAssembly.accept(root);
            }
            // 节奏化输入（M23 工单 01 事件驱动）：管道 + 供给线程按节奏写行——
            // 默认等第 N 个 idle 提示符（模拟真人逐轮输入），标记行等 busy 期信号
            java.io.PipedOutputStream pipe = new java.io.PipedOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    new java.io.PipedInputStream(pipe, 4096), StandardCharsets.UTF_8));
            // 声明 config 类型即须提供 config 块（内核严格绑定，字段可省）
            root.plugin(new CliPlugin(in, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                    sessionsDir, llm), JsonNodeFactory.instance.objectNode()).awaitStartup();
            startFeeder(pipe);
        }

        private static List<InputLine> splitLines(String scriptedInput) {
            return java.util.Arrays.stream(scriptedInput.split("\n"))
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .map(InputLine::of)
                    .toList();
        }

        /** 输入供给线程：逐行等待节奏点（提示符计数或标记出现）后写入，写尽关管即 EOF。 */
        private void startFeeder(java.io.PipedOutputStream pipe) {
            Thread.ofVirtual().name("cli-input-feeder").start(() -> {
                try {
                    for (int i = 0; i < lines.size(); i++) {
                        InputLine line = lines.get(i);
                        long deadline = System.currentTimeMillis() + 10_000;
                        boolean ready = false;
                        while (System.currentTimeMillis() < deadline) {
                            ready = line.awaitMarker() != null
                                    ? output().contains(line.awaitMarker())
                                    : promptCount(output()) >= i + 1;
                            if (ready) {
                                break;
                            }
                            Thread.sleep(20);
                        }
                        if (!ready) {
                            throw new IllegalStateException("第 " + i + " 行（" + line.text()
                                    + "）未在时限内等到节奏点");
                        }
                        pipe.write((line.text() + "\n").getBytes(StandardCharsets.UTF_8));
                        pipe.flush();
                    }
                } catch (Exception e) {
                    feederError = e.getMessage() == null ? e.toString() : e.getMessage();
                } finally {
                    try {
                        pipe.close(); // 写尽即 EOF（读者线程 readLine 返回 null）
                    } catch (java.io.IOException ignored) {
                        // 已关
                    }
                }
            });
        }

        /** 输出中 idle 提示符「你>」的出现次数（默认节奏的基准）。 */
        private static int promptCount(String output) {
            int count = 0;
            int idx = 0;
            while ((idx = output.indexOf("你> ", idx)) >= 0) {
                count++;
                idx += 3;
            }
            return count;
        }

        String output() {
            return outBuf.toString(StandardCharsets.UTF_8);
        }

        /** 交互服务（idle 后回答者摘除断言用）。 */
        dev.duo.harness.tools.InteractionService answers() {
            return root.as(AnswersView.class).answers();
        }

    /** 需审批的测试工具（写操作守卫）：执行返回 "written"。 */
            void registerGuardedWriteTool() {
            dev.duo.harness.tools.ToolsService tools = root.as(ToolsView.class).tools();
            tools.register(root, new dev.duo.harness.tools.ToolDefinition() {
                @Override
                public String name() {
                    return "guarded_write";
                }

                @Override
                public String description() {
                    return "需审批的写入";
                }

                @Override
                public com.fasterxml.jackson.databind.JsonNode parameters() {
                    return JsonNodeFactory.instance.objectNode().put("type", "object");
                }

                @Override
                public boolean requiresApproval() {
                    return true;
                }

                @Override
                public String execute(dev.duo.harness.tools.ToolExecution execution) {
                    return "written";
                }
            });
        }



        /** 等 REPL 进入 idle（输出出现收尾行，最多 10 秒）。 */
        void awaitIdle() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                if (feederError != null) {
                    throw new AssertionError("输入供给线程失败: " + feederError + "\n输出:\n" + output());
                }
                if (output().contains("=== 对话结束 ===")) {
                    return;
                }
                Thread.sleep(50);
            }
            if (feederError != null) {
                throw new AssertionError("输入供给线程失败: " + feederError + "\n输出:\n" + output());
            }
            throw new AssertionError("REPL 未在时限内进入 idle，输出:\n" + output());
        }

        void dispose() {
            root.dispose();
        }
    }

    /** 固定直答 mock LLM。 */
    private static LlmAdapter fixedReply(String reply) {
        AtomicInteger calls = new AtomicInteger();
        return new LlmAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                onChunk.accept(new ChatChunk(reply));
            }

            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                calls.incrementAndGet();
                textSink.accept(reply);
                return new LlmTurn(reply, List.of());
            }
        };
    }

    @Test
    void replTurnLogsSessionEventsAndExitsIdle() throws Exception {
        // 基本循环：一轮对话事件落会话；/exit 经命令注册表（M19）——run/done 审计落盘后 idle
        Path dir = tempDir.resolve("a");
        Fixture fx = new Fixture(dir, "问好\n/exit\n", fixedReply("答：好"));
        try {
            fx.awaitIdle();
            String out = fx.output();
            assertTrue(out.contains("会话 "), out);
            assertTrue(out.contains("答：好"), out);
            assertTrue(out.contains("=== 对话结束 ==="), out);

            Session latest = Session.latest(dir); // idle 已释放锁，latest 可正常打开
            // 标题生成（工单 M13-06）异步落盘，轮询等待（竞态避免）
            long titleDeadline = System.currentTimeMillis() + 5_000;
            while (latest.events().size() < 5 && System.currentTimeMillis() < titleDeadline) {
                Thread.sleep(50);
                latest.close();
                latest = Session.latest(dir);
            }
            assertEquals(5, latest.events().size(),
                    "user/message + assistant/message + session/title + /exit 的 run/done 审计");
            assertEquals("问好", latest.events().get(0).text());
            // 标题生成异步——title 与 assistant/message 落日志顺序不保证，按类型集合断言
            var types = latest.events().stream().map(SessionEvent::type).sorted().toList();
            assertEquals(List.of(SessionEvent.ASSISTANT_MESSAGE, SessionEvent.COMMAND_DONE,
                            SessionEvent.COMMAND_RUN, SessionEvent.TITLE, SessionEvent.USER_MESSAGE),
                    types, "五类事件齐备");
            SessionEvent exitRun = latest.events().stream()
                    .filter(e -> SessionEvent.COMMAND_RUN.equals(e.type())).findFirst().orElseThrow();
            assertEquals("exit", exitRun.toolName(), "/exit 经命令注册表执行");
            latest.close();
        } finally {
            fx.dispose();
        }
    }

    @Test
    void newCommandSwitchesToFreshSession() throws Exception {
        // /new 换绑（M19 经注册表）：旧会话立即关闭（锁释放）、run 落旧会话收尾、
        // done 随新会话开篇、后续对话落新会话
        Path dir = tempDir.resolve("b");
        Fixture fx = new Fixture(dir, "第一问\n/new\n第二问\n/exit\n", fixedReply("答"));
        try {
            fx.awaitIdle();
            assertTrue(fx.output().contains("新会话 "), fx.output());

            // 两个会话文件：旧的含第一轮，新的（最新修改）含第二轮
            var summaries = Session.list(dir);
            assertEquals(2, summaries.size(), "旧 + 新两个会话");
            Session fresh = Session.load(summaries.get(0).jsonl()); // 修改时间倒序：最新在前
            Session old = Session.load(summaries.get(1).jsonl());
            assertEquals(SessionEvent.COMMAND_DONE, fresh.events().get(0).type(),
                    "/new 的 done 审计随新会话开篇");
            assertEquals("new", fresh.events().get(0).toolName());
            assertEquals("第二问", fresh.events().get(1).text());
            assertEquals(SessionEvent.COMMAND_RUN, old.events().stream()
                    .filter(e -> SessionEvent.COMMAND_RUN.equals(e.type())).findFirst().orElseThrow()
                    .type(), "/new 的 run 审计留在旧会话（换绑两头留痕）");
            assertEquals("第一问", old.events().get(0).text());
            fresh.close();
            old.close();
        } finally {
            fx.dispose();
        }
    }

    @Test
    void eofGoesIdleAndDetachesAnswerer() throws Exception {
        // EOF（输入流读尽）与 /exit 同语义：idle——会话锁释放、回答者摘除（无人在场
        // 的交互请求立即 fail-closed 而非阻塞等待）
        Path dir = tempDir.resolve("d");
        Fixture fx = new Fixture(dir, "问\n", fixedReply("答"));  // 无 /exit：读尽即 EOF
        try {
            fx.awaitIdle();
            assertTrue(fx.output().contains("=== 对话结束 ==="), fx.output());

            // 回答者已摘除：ask 立即 fail-closed（有回答者在场会阻塞等人）
            var answer = fx.answers().ask(dev.duo.harness.tools.InteractionRequest.approval("x", "{}"));
            assertEquals(dev.duo.harness.tools.InteractionAnswer.SOURCE_FAIL_CLOSED, answer.source(),
                    "idle 后无人应答即拒");

            Session latest = Session.latest(dir); // 锁已释放可重开
            assertEquals(3, latest.events().size(), "user/message + assistant/message + session/title（工单 M13-06）");
            latest.close();
        } finally {
            fx.dispose();
        }
    }

    @Test
    void approvalAnsweredFromReplInputStream() throws Exception {
        // REPL 级审批：mock LLM 第一轮发起需审批的工具调用 → ConsoleAnswerer 呈现
        // y/n 并读同一输入流的下一行（"y"）→ 放行 → 第二轮直答
        Path dir = tempDir.resolve("e");
        dev.duo.harness.llm.LlmAdapter llm = new dev.duo.harness.llm.LlmAdapter() {
            int turn = 0;

            @Override
            public void stream(dev.duo.harness.llm.ChatRequest request,
                               java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.duo.harness.llm.LlmTurn streamTurn(dev.duo.harness.llm.ChatRequest request,
                                                           java.util.function.Consumer<String> textSink) {
                turn++;
                if (turn == 1) {
                    return new dev.duo.harness.llm.LlmTurn("", List.of(
                            new dev.duo.harness.llm.ToolCallRequest("call_1", "guarded_write", "{}")));
                }
                textSink.accept("已执行");
                return new dev.duo.harness.llm.LlmTurn("已执行", List.of());
            }
        };
        // 输入流：第一行触发工具调用，第二行是审批的 "y"（busy 期按 [待审批] 标记投递——
        // 应答行经事件驱动读者线程的应答闸门路由给 ConsoleAnswerer，M23 工单 01）
        Fixture fx = new Fixture(dir, List.of(InputLine.of("写入"), InputLine.paced("y", "[待审批]"),
                InputLine.of("/exit")), llm);
        try {
            fx.awaitIdle();
            String out = fx.output();
            assertTrue(out.contains("[待审批] 工具 guarded_write"), "审批呈现: " + out);
            assertTrue(out.contains("[工具结果] written"), "审批放行后工具执行: " + out);
        } finally {
            fx.dispose();
        }
    }

    @Test
    void permissionCommandShowsAndSwitchesPreset() throws Exception {
        // /permission（M12-02）：无参查看当前档位；带参切换——切的是 fs 插件发布的
        // 同一 WorkspacePolicy 实例（fixture 以 read-only 起步）
        Path dir = tempDir.resolve("f");
        Fixture fx = new Fixture(dir, "/permission\n/permission workspace-write\n/exit\n", fixedReply("答"));
        try {
            fx.awaitIdle();
            String out = fx.output();
            assertTrue(out.contains("当前预设: read-only"), out);
            assertTrue(out.contains("已切换: workspace-write"), out);
        } finally {
            fx.dispose();
        }
    }

    @Test
    void unknownCommandListsRegisteredCommands() throws Exception {
        // 未知命令（M19 入口顺序第三级）：报错附可用命令清单（技能清单在场时附注——
        // 本仓库 .agents/skills 存在故技能段非空，不参与断言）
        Path dir = tempDir.resolve("unknown");
        Fixture fx = new Fixture(dir, "/nope\n/exit\n", fixedReply("答"));
        try {
            fx.awaitIdle();
            String out = fx.output();
            assertTrue(out.contains("未知命令: /nope（可用命令: export, exit, stop, new, permission, compact, title, plan"),
                    "六命令注册序即清单序（/stop 插在 exit 后）: " + out);
        } finally {
            fx.dispose();
        }
    }

    @Test
    void planCommandEntersExitsAndForwardsTaskText() throws Exception {
        // /plan 迁移后行为不变：进入、退出、携任务描述续接（回显进入提示后把描述
        // 作为普通输入推进 agent——转发文本走 user/message 进模型历史）
        Path dir = tempDir.resolve("plan");
        Fixture fx = new Fixture(dir, "/plan\n/plan off\n/plan 帮我调研\n/exit\n", fixedReply("答：调研完了"));
        try {
            fx.awaitIdle();
            String out = fx.output();
            assertTrue(out.contains("已进入计划模式（先探索与设计"), out);
            assertTrue(out.contains("已退出计划模式。"), out);
            assertTrue(out.contains("答：调研完了"), "转发文本推进 agent 并回流回答: " + out);

            Session latest = Session.latest(dir);
            var planEvents = latest.events().stream()
                    .filter(e -> "plan/mode".equals(e.type())).map(SessionEvent::text).toList();
            assertEquals(List.of("entered", "exited", "entered"), planEvents,
                    "两次进入一次退出的计划态事件序列");
            assertTrue(latest.events().stream().anyMatch(e ->
                            "user/message".equals(e.type()) && "帮我调研".equals(e.text())),
                    "转发文本以普通用户消息落盘");
            latest.close();
        } finally {
            fx.dispose();
        }
    }

    @Test
    void bashToolRendersThroughGenericToolLines() throws Exception {
        // 呈现零特化回归（M12-03）：bash 走与其他工具同一条叙述/结果行通路——
        // 终端不认识工具名，行文由通用 onToolCall/onToolResult 产出（无 bash 分支）；
        // 本 fixture 为 read-only 档 → 先经终端审批，y 放行后才执行
        Path dir = tempDir.resolve("g");
        dev.duo.harness.llm.LlmAdapter llm = new dev.duo.harness.llm.LlmAdapter() {
            int turn = 0;

            @Override
            public void stream(dev.duo.harness.llm.ChatRequest request,
                               java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.duo.harness.llm.LlmTurn streamTurn(dev.duo.harness.llm.ChatRequest request,
                                                           java.util.function.Consumer<String> textSink) {
                turn++;
                if (turn == 1) {
                    return new dev.duo.harness.llm.LlmTurn("", List.of(
                            new dev.duo.harness.llm.ToolCallRequest(
                                    "call_1", "bash", "{\"command\":\"echo 呈现回归\"}")));
                }
                textSink.accept("完成");
                return new dev.duo.harness.llm.LlmTurn("完成", List.of());
            }
        };
        Fixture fx = new Fixture(dir, List.of(InputLine.of("跑个命令"), InputLine.paced("y", "[待审批]"),
                InputLine.of("/exit")), llm);
        try {
            fx.awaitIdle();
            String out = fx.output();
            assertTrue(out.contains("[待审批] 工具 bash"), "read-only 档 bash 经终端审批: " + out);
            assertTrue(out.contains("[调工具] bash {\"command\":\"echo 呈现回归\"}"),
                    "通用叙述行原样含工具名与参数: " + out);
            assertTrue(out.contains("[工具结果] 呈现回归"), "通用结果行回填命令输出: " + out);
        } finally {
            fx.dispose();
        }
    }

    @Test
    void busyInputSteersIntoNextStepAndReachesNextRequest() throws Exception {
        // 执行期键入（M23 工单 01，ADR-0025 决策一）：busy 期普通文本注入收件箱
        // next-step 级，提示行回显「已插队」，下一轮请求可见——不再被静默当新输入消费。
        // 探针工具阻塞执行制造稳定 busy 窗口：插队确认出现后才放行工具，注入必在边界前
        Path dir = tempDir.resolve("steer");
        CountDownLatch probeRelease = new CountDownLatch(1);
        AtomicReference<dev.duo.harness.llm.ChatRequest> secondRequest = new AtomicReference<>();
        dev.duo.harness.llm.LlmAdapter llm = new dev.duo.harness.llm.LlmAdapter() {
            int turn = 0;

            @Override
            public void stream(dev.duo.harness.llm.ChatRequest request,
                               java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.duo.harness.llm.LlmTurn streamTurn(dev.duo.harness.llm.ChatRequest request,
                                                          java.util.function.Consumer<String> textSink) {
                turn++;
                if (turn == 1) {
                    // 先流出一段文本再调阻塞探针——「开始执行」即稳定 busy 期标记
                    // （[调工具] 在工具执行完成后才打印，不能作投递节奏标记）
                    textSink.accept("开始执行");
                    return new dev.duo.harness.llm.LlmTurn("", List.of(
                            new dev.duo.harness.llm.ToolCallRequest("call_1", "probe", "{}")));
                }
                secondRequest.set(request);
                textSink.accept("最终回答");
                return new dev.duo.harness.llm.LlmTurn("最终回答", List.of());
            }
        };
        Fixture fx = new Fixture(dir, List.of(InputLine.of("问"), InputLine.paced("插队提示", "开始执行"),
                InputLine.of("/exit")), llm, ctx -> ctx.as(ToolsView.class).tools().register(ctx,
                blockingProbe(probeRelease)));
        try {
            long deadline = System.currentTimeMillis() + 10_000;
            while (!fx.output().contains("[已插队]") && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(fx.output().contains("[已插队] 插队提示"),
                    "插队回显可见: " + fx.output());
            probeRelease.countDown();
            fx.awaitIdle();

            assertTrue(secondRequest.get().messages().stream()
                            .anyMatch(m -> m.content() != null && m.content().contains("插队提示")),
                    "插队文本进下一轮请求（模型下一步可见）");
            assertTrue(fx.output().contains("[工具结果] probe-done"),
                    "飞行中工具组照常执行: " + fx.output());
        } finally {
            fx.dispose();
        }
    }

    @Test
    void busyCommandRefusesNonBusySafeAndRunsBusySafe() throws Exception {
        // 执行期命令分级（ADR-0020 决策 4 在事件驱动 CLI 真正生效）：busySafe 命令
        // 即行（/permission 查看），非 busySafe 得到等待回应（/exit 被拒），空闲后
        // 再 /exit 正常退出
        Path dir = tempDir.resolve("busy-cmd");
        CountDownLatch probeRelease = new CountDownLatch(1);
        dev.duo.harness.llm.LlmAdapter llm = new dev.duo.harness.llm.LlmAdapter() {
            int turn = 0;

            @Override
            public void stream(dev.duo.harness.llm.ChatRequest request,
                               java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.duo.harness.llm.LlmTurn streamTurn(dev.duo.harness.llm.ChatRequest request,
                                                          java.util.function.Consumer<String> textSink) {
                turn++;
                if (turn == 1) {
                    // 先流出标记文本再调阻塞探针（busy 窗口锚点，理由同上）
                    textSink.accept("开始执行");
                    return new dev.duo.harness.llm.LlmTurn("", List.of(
                            new dev.duo.harness.llm.ToolCallRequest("call_1", "probe", "{}")));
                }
                textSink.accept("完成");
                return new dev.duo.harness.llm.LlmTurn("完成", List.of());
            }
        };
        Fixture fx = new Fixture(dir, List.of(InputLine.of("问"), InputLine.paced("/permission", "开始执行"),
                InputLine.paced("/exit", "开始执行"), InputLine.of("/exit")), llm,
                ctx -> ctx.as(ToolsView.class).tools().register(ctx, blockingProbe(probeRelease)));
        try {
            long deadline = System.currentTimeMillis() + 10_000;
            String out;
            while (System.currentTimeMillis() < deadline) {
                out = fx.output();
                if (out.contains("当前预设: read-only") && out.contains("agent 执行中，需等待空闲")) {
                    break;
                }
                Thread.sleep(20);
            }
            out = fx.output();
            assertTrue(out.contains("当前预设: read-only"), "busySafe 命令执行期即行: " + out);
            assertTrue(out.contains("agent 执行中，需等待空闲"), "非 busySafe 命令得到等待回应: " + out);
            probeRelease.countDown();
            fx.awaitIdle();
        } finally {
            fx.dispose();
        }
    }

    @Test
    void eofDuringBusyWaitsForTurnCompletion() throws Exception {
        // EOF 不腰斩执行中的 turn（M23 事件驱动语义）：读者线程读到 EOF 时 turn 在飞
        // ——join 等收尾再 idle，最终回答完整落盘
        Path dir = tempDir.resolve("eof-busy");
        CountDownLatch releaseTurn = new CountDownLatch(1);
        dev.duo.harness.llm.LlmAdapter llm = new dev.duo.harness.llm.LlmAdapter() {
            @Override
            public void stream(dev.duo.harness.llm.ChatRequest request,
                               java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.duo.harness.llm.LlmTurn streamTurn(dev.duo.harness.llm.ChatRequest request,
                                                          java.util.function.Consumer<String> textSink) {
                try {
                    releaseTurn.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                textSink.accept("慢回答");
                return new dev.duo.harness.llm.LlmTurn("慢回答", List.of());
            }
        };
        Fixture fx = new Fixture(dir, List.of(InputLine.of("问")), llm);
        try {
            Thread.sleep(200); // 让 EOF 先于释放到达（读者线程进入 join 等待）
            releaseTurn.countDown();
            fx.awaitIdle();
            assertTrue(fx.output().contains("慢回答"), "EOF 不腰斩：最终回答完整呈现: " + fx.output());
        } finally {
            fx.dispose();
        }
    }

    /** 阻塞探针工具：执行等待放行闩（制造稳定 busy 窗口），只读可并发。 */
    private static dev.duo.harness.tools.ToolDefinition blockingProbe(CountDownLatch release) {
        return new dev.duo.harness.tools.ToolDefinition() {
            @Override
            public String name() {
                return "probe";
            }

            @Override
            public String description() {
                return "只读探针";
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode parameters() {
                return JsonNodeFactory.instance.objectNode().put("type", "object");
            }

            @Override
            public boolean isConcurrencySafe(com.fasterxml.jackson.databind.JsonNode args) {
                return true;
            }

            @Override
            public Object execute(dev.duo.harness.tools.ToolExecution execution) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return "probe-done";
            }
        };
    }

    @Test
    void stopCommandInterruptsBusyTurnAndResumesOnNextMessage() throws Exception {
        // /stop 协作式中断（M23 工单 02）：busy 期投递 /stop（busySafe 即行）→ 慢工具
        // 被打断收口（[已中断] 行 + 会话事件 assistant/interrupted + 未派发调用合成）；
        // 下一条消息即续接（可恢复态）
        Path dir = tempDir.resolve("stop");
        CountDownLatch probeRelease = new CountDownLatch(1);
        dev.duo.harness.llm.LlmAdapter llm = new dev.duo.harness.llm.LlmAdapter() {
            int turn = 0;

            @Override
            public void stream(dev.duo.harness.llm.ChatRequest request,
                               java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.duo.harness.llm.LlmTurn streamTurn(dev.duo.harness.llm.ChatRequest request,
                                                          java.util.function.Consumer<String> textSink) {
                turn++;
                if (turn == 1) {
                    textSink.accept("开始执行");
                    return new dev.duo.harness.llm.LlmTurn("", List.of(
                            new dev.duo.harness.llm.ToolCallRequest("call_1", "probe", "{}"),
                            new dev.duo.harness.llm.ToolCallRequest("call_2", "probe", "{}")));
                }
                textSink.accept("续接回答");
                return new dev.duo.harness.llm.LlmTurn("续接回答", List.of());
            }
        };
        Fixture fx = new Fixture(dir, List.of(
                InputLine.of("问"),
                InputLine.paced("/stop", "开始执行"),
                InputLine.paced("继续", "[已中断]"),
                InputLine.of("/exit")), llm,
                ctx -> ctx.as(ToolsView.class).tools().register(ctx, blockingProbe(probeRelease)));
        try {
            long deadline = System.currentTimeMillis() + 10_000;
            while (!fx.output().contains("[已中断]") && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            String out = fx.output();
            assertTrue(out.contains("已请求中断当前任务"), "/stop 回执: " + out);
            assertTrue(out.contains("[已中断] 当前任务已暂停"), "中断收口行: " + out);
            probeRelease.countDown(); // 双保险放行（探针即使未被打断也能收尾）
            fx.awaitIdle();
            assertTrue(fx.output().contains("续接回答"), "中断后续接即继续: " + fx.output());

            Session latest = Session.latest(dir);
            assertTrue(latest.events().stream().anyMatch(e ->
                            SessionEvent.ASSISTANT_INTERRUPTED.equals(e.type())),
                    "中断标记落会话日志");
            assertTrue(latest.events().stream().anyMatch(e ->
                            SessionEvent.TOOL_RESULT.equals(e.type())
                                    && e.text().contains("[interrupted]")),
                    "未派发调用补合成结果");
            latest.close();
        } finally {
            fx.dispose();
        }
    }

    @Test
    void stopCommandWhenIdleExplains() throws Exception {
        // /stop 空闲态：无任务可中断，明确提示（fail-safe 不误报）
        Path dir = tempDir.resolve("stop-idle");
        Fixture fx = new Fixture(dir, List.of(InputLine.of("/stop"), InputLine.of("/exit")),
                fixedReply("答"));
        try {
            fx.awaitIdle();
            assertTrue(fx.output().contains("当前无执行中任务"), fx.output());
        } finally {
            fx.dispose();
        }
    }

    @Test
    void occupiedLatestSessionPrintsHintAndOpensNew() throws Exception {
        // 占用语义（M10-03 延续）：最新会话被他处持有 → 明确提示 + 改开新会话（绝不静默共享日志）
        Path dir = tempDir.resolve("c");
        Session occupied = Session.create(dir);
        occupied.append(SessionEvent.userMessage("被占会话"));

        Fixture fx = new Fixture(dir, "/exit\n", fixedReply("答"));
        try {
            fx.awaitIdle();
            String out = fx.output();
            assertTrue(out.contains("会话已被占用：" + occupied.id()), "点名被占会话: " + out);
            assertTrue(out.contains("改为新建会话继续"), out);

            var summaries = Session.list(dir);
            assertEquals(2, summaries.size(), "被占 + 新开");
            assertEquals(occupied.id(), summaries.get(1).id(), "被占会话未被改动（只剩原一条事件）");
            occupied.append(SessionEvent.userMessage("属主继续写"));
            assertEquals(2, occupied.events().size(), "属主不受影响");
        } finally {
            fx.dispose();
        }
    }

    @Test
    void compactCommandCompactsAndLandsCompactionEvent() throws Exception {
        // /compact（M19，ADR-0020 决策 6）：命令触发治理压缩——mock LLM 直答形态返回
        // 摘要，manual 压缩点落会话，回显压缩结果；摘要经 stream() 直答（compaction 专用）
        Path dir = tempDir.resolve("compact");
        dev.duo.harness.llm.LlmAdapter llm = new dev.duo.harness.llm.LlmAdapter() {
            @Override
            public void stream(dev.duo.harness.llm.ChatRequest request,
                               java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                onChunk.accept(new dev.duo.harness.llm.ChatChunk("## 主要请求\n压缩总结"));
            }

            @Override
            public dev.duo.harness.llm.LlmTurn streamTurn(dev.duo.harness.llm.ChatRequest request,
                                                          java.util.function.Consumer<String> textSink) {
                textSink.accept("答");
                return new dev.duo.harness.llm.LlmTurn("答", List.of());
            }
        };
        // 五轮对话让远端越过最小折叠量（近端之外不足 4 条时提示无需压缩）
        Fixture fx = new Fixture(dir,
                "一问\n二问\n三问\n四问\n五问\n/compact\n/exit\n", llm);
        try {
            fx.awaitIdle();
            String out = fx.output();
            assertTrue(out.contains("已压缩"), "回显压缩结果: " + out);

            Session latest = Session.latest(dir);
            var compacted = latest.events().stream()
                    .filter(e -> SessionEvent.COMPACTION.equals(e.type())).findFirst().orElseThrow();
            assertEquals("manual", compacted.toolName(), "/compact 触发署名 manual");
            assertTrue(compacted.text().contains("压缩总结"), "总结全文随事件落盘");
            latest.close();
        } finally {
            fx.dispose();
        }
    }

    @Test
    void permissionModePersistsAcrossSessionReopen() throws Exception {
        // 切档持久化（M19，ADR-0020 决策 10）：切档落 permission/mode 事件——重开该
        // 会话恢复最后切定档（fixture 装配档为 read-only，重开应显示 workspace-write）
        Path dir = tempDir.resolve("persist");
        Fixture fx = new Fixture(dir, "/permission workspace-write\n/exit\n", fixedReply("答"));
        try {
            fx.awaitIdle();
        } finally {
            fx.dispose();
        }

        Fixture reopened = new Fixture(dir, "/permission\n/exit\n", fixedReply("答"));
        try {
            reopened.awaitIdle();
            assertTrue(reopened.output().contains("当前预设: workspace-write"),
                    "重开恢复最后切定档（非装配档 read-only）: " + reopened.output());
        } finally {
            reopened.dispose();
        }
    }

    @Test
    void titleCommandRenamesSessionViaLatestWins() throws Exception {
        // /title（M19，ADR-0020 决策 11）：再 append title 事件即改名（latest-wins）
        Path dir = tempDir.resolve("title");
        Fixture fx = new Fixture(dir, "/title 我的重要会话\n/exit\n", fixedReply("答"));
        try {
            fx.awaitIdle();
            assertTrue(fx.output().contains("已改名: 我的重要会话"), fx.output());
            Session latest = Session.latest(dir);
            assertEquals("我的重要会话", latest.title(), "title 事件落盘（改名生效）");
            latest.close();
        } finally {
            fx.dispose();
        }
    }

    @Test
    void occupiedSessionInheritsPermissionModeIntoNewSession() throws Exception {
        // BUG-20260919-03 裁定延续（M19-06）：占用改开的新会话继承被占会话最后切定档
        // 并落 permission/mode 事件——治理态不因呈现位轮转而丢（重启链延续）
        Path dir = tempDir.resolve("inherit");
        Session occupied = Session.create(dir);
        occupied.append(SessionEvent.permissionMode("danger-full-access"));

        Fixture fx = new Fixture(dir, "/permission\n/exit\n", fixedReply("答"));
        try {
            fx.awaitIdle();
            assertTrue(fx.output().contains("已继承被占会话的权限档: danger-full-access"),
                    "继承提示可见: " + fx.output());
            assertTrue(fx.output().contains("当前预设: danger-full-access"),
                    "继承档立即生效: " + fx.output());
            Session latest = Session.latest(dir);
            assertEquals("danger-full-access", latest.permissionMode(),
                    "新会话落继承记录（重启链延续）");
            latest.close();
        } finally {
            fx.dispose();
        }
        occupied.append(SessionEvent.userMessage("属主继续写"));
        occupied.close();
    }

    @Test
    void subagentLifecyclePrintsTraceLines() throws Exception {
        // M15 工单 05：CLI 子任务过程行——spawn 派生与子代理完成（后台回流）各打一行，
        // 完成行携最终回答；子代理经真实内嵌后端 + mock LLM（按 system 分流父/子）
        Path dir = tempDir.resolve("subagent");
        LlmAdapter llm = subagentScriptedLlm(dir);
        Fixture fx = new Fixture(dir, "派个活\n/exit\n", llm, ctx -> {
            // 模板制装配前置：subagent 插件（模板 worker 带专属提示——mock 据此分流）
            var template = JsonNodeFactory.instance.objectNode()
                    .put("name", "worker")
                    .put("prompt", "子代理专用提示");
            template.putArray("tools").add("echo");
            var config = JsonNodeFactory.instance.objectNode();
            config.putArray("templates").add(template);
            ctx.plugin(new dev.duo.harness.agent.subagent.SubagentPlugin(), config);
            ctx.as(ToolsView.class).tools().register(ctx, new dev.duo.harness.tools.ToolDefinition() {
                @Override
                public String name() {
                    return "echo";
                }

                @Override
                public String description() {
                    return "回声";
                }

                @Override
                public com.fasterxml.jackson.databind.JsonNode parameters() {
                    return JsonNodeFactory.instance.objectNode().put("type", "object");
                }

                @Override
                public Object execute(dev.duo.harness.tools.ToolExecution execution) {
                    return "echo";
                }
            });
        });
        try {
            fx.awaitIdle();
            String out = fx.output();
            assertTrue(out.contains("[子任务] 已派生子代理"), "派生过程行可见:\n" + out);
            assertTrue(out.contains("（模板 worker）"), out);
            assertTrue(out.contains("[子任务] 子代理") && out.contains("已完成"), "完成过程行可见:\n" + out);
            assertTrue(out.contains("子代理结论：方案 A 可行"), "完成行携最终回答:\n" + out);
        } finally {
            fx.dispose();
        }
    }

    /**
     * 父/子共用 adapter 的脚本（按 systemPrompt 分流——子代理 system 即模板专属提示，
     * 不继承父的 userPrompt）：父首轮调 spawn，其后等子代理子会话锁释放（完成回流
     * 已落父会话）再直答；子代理直答结论。
     */
    private static LlmAdapter subagentScriptedLlm(Path sessionsDir) {
        AtomicInteger parentCalls = new AtomicInteger();
        return new LlmAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                onChunk.accept(new ChatChunk("x"));
            }

            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                if (request.systemPrompt().contains("子代理专用提示")) {
                    textSink.accept("子代理结论：方案 A 可行");
                    return new LlmTurn("子代理结论：方案 A 可行", List.of());
                }
                if (parentCalls.incrementAndGet() == 1) {
                    return new LlmTurn("", List.of(new dev.duo.harness.llm.ToolCallRequest("p1", "spawn",
                            "{\"template\":\"worker\",\"task\":\"调研 X\"}")));
                }
                awaitChildrenReleased(sessionsDir);
                textSink.accept("已派子代理在后台执行。");
                return new LlmTurn("已派子代理在后台执行。", List.of());
            }
        };
    }

    /** 轮询等全部子会话锁释放（子代理完成后释放，其 completed 回流必已在先）。 */
    private static void awaitChildrenReleased(Path sessionsDir) {
        Path subagents = sessionsDir.resolve("subagents");
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (java.nio.file.Files.isDirectory(subagents)) {
                    var files = java.nio.file.Files.list(subagents)
                            .filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList();
                    if (!files.isEmpty() && files.stream().noneMatch(Session::isOccupied)) {
                        return;
                    }
                }
                Thread.sleep(50);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("子代理未在时限内完成");
    }
}
