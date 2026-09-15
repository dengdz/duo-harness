package dev.duo.harness.cli;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import dev.duo.harness.agent.PromptPlugin;
import dev.duo.harness.agent.SkillsPlugin;
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
import java.util.concurrent.atomic.AtomicInteger;

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
        System.out.println("\n=== 套件：CliPluginTest —— CLI 呈现位插件：REPL 循环、/new 换绑、"
                + "/exit idle 锁释放、占用提示、/permission 档位、工具叙述行通用形态（7 用例） ===");
    }

    interface ToolsView {

        dev.duo.harness.tools.ToolsService tools();
    }

    interface AnswersView {

        dev.duo.harness.tools.InteractionService answers();
    }

    @TempDir
    Path tempDir;

    /** 脚本化 REPL 夹具：注入输入/mock LLM/临时会话目录，跑完脚本等 idle。 */
    private static final class Fixture {
        final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        final Context root = Context.root();
        final Path sessionsDir;

        Fixture(Path sessionsDir, String scriptedInput, LlmAdapter llm) throws Exception {
            this.sessionsDir = sessionsDir;
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            root.plugin(new PromptPlugin(), JsonNodeFactory.instance.objectNode()
                    .put("systemPrompt", "测试提示")).awaitStartup();
            root.plugin(new InteractionPlugin(), null).awaitStartup();
            root.plugin(new SkillsPlugin(), JsonNodeFactory.instance.objectNode()
                    .putArray("disabled")).awaitStartup();
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
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(scriptedInput.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8));
            // 声明 config 类型即须提供 config 块（内核严格绑定，字段可省）
            root.plugin(new CliPlugin(in, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                    sessionsDir, llm), JsonNodeFactory.instance.objectNode()).awaitStartup();
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
                if (output().contains("=== 对话结束 ===")) {
                    return;
                }
                Thread.sleep(50);
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
        // 基本循环：一轮对话事件落会话；/exit → idle（输出收尾行）
        Path dir = tempDir.resolve("a");
        Fixture fx = new Fixture(dir, "问好\n/exit\n", fixedReply("答：好"));
        try {
            fx.awaitIdle();
            String out = fx.output();
            assertTrue(out.contains("会话 "), out);
            assertTrue(out.contains("答：好"), out);
            assertTrue(out.contains("=== 对话结束 ==="), out);

            Session latest = Session.latest(dir); // idle 已释放锁，latest 可正常打开
            // 标题生成（工单 M13-06）为 title 事件多落一条——异步落盘，轮询等待（竞态避免）
            long titleDeadline = System.currentTimeMillis() + 5_000;
            while (latest.events().size() < 3 && System.currentTimeMillis() < titleDeadline) {
                Thread.sleep(50);
                latest.close();
                latest = Session.latest(dir);
            }
            assertEquals(3, latest.events().size(), "user/message + assistant/message + session/title");
            assertEquals("问好", latest.events().get(0).text());
            // 标题生成异步——title 与 assistant/message 落日志顺序不保证，按类型集合断言
            var types = latest.events().stream().map(SessionEvent::type).sorted().toList();
            assertEquals(List.of(SessionEvent.ASSISTANT_MESSAGE, SessionEvent.TITLE, SessionEvent.USER_MESSAGE),
                    types, "三类事件齐备");
            latest.close();
        } finally {
            fx.dispose();
        }
    }

    @Test
    void newCommandSwitchesToFreshSession() throws Exception {
        // /new 换绑：旧会话立即关闭（锁释放）、后续对话落新会话
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
            assertEquals("第二问", fresh.events().get(0).text());
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
        // 输入流：第一行触发工具调用，第二行是审批的 "y"
        Fixture fx = new Fixture(dir, "写入\ny\n/exit\n", llm);
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
        Fixture fx = new Fixture(dir, "跑个命令\ny\n/exit\n", llm);
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
}
