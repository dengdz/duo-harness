package dev.duo.harness.cli;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.agent.prompt.PromptPlugin;
import dev.duo.harness.agent.skills.SkillsPlugin;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.llm.ChatChunk;
import dev.duo.harness.llm.ChatRequest;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.llm.LlmTurn;
import dev.duo.harness.tools.InteractionPlugin;
import dev.duo.harness.tools.ToolsPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯对话装配的 REPL 用例（ADR-0019 工单 01 验收）：不挂 fs 工具族（无 workspace
 * 服务提供方），脚本驱动 `/permission`——CLI 照常聊天、命令降级"未挂载"提示，
 * 而非整树 PENDING 起不来。对照 CliPluginTest（其夹具挂 fs，验证有 workspace 的常态）。
 */
class CliOptionalWorkspaceTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：CliOptionalWorkspaceTest —— 纯对话装配：无 workspace 时"
                + "聊天照常、/permission 降级未挂载（1 用例） ===");
    }

    @TempDir
    Path tempDir;

    @Test
    void chatWorksWithoutWorkspaceAndPermissionDegrades() throws Exception {
        // 脚本：先聊一句（证明 REPL 存活），再 /permission（降级提示），最后 /exit 收尾
        String scripted = "你好\n/permission\n/exit\n";
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        Context root = Context.root();
        try {
            // 有意不挂 FsToolsPlugin / WorkspaceApprovalPlugin：纯对话装配无 workspace 提供方
            root.plugin(new ToolsPlugin(), null).awaitStartup();
            root.plugin(new PromptPlugin(), JsonNodeFactory.instance.objectNode()
                    .put("systemPrompt", "测试提示")).awaitStartup();
            root.plugin(new InteractionPlugin(), null).awaitStartup();
            root.plugin(new SkillsPlugin(), JsonNodeFactory.instance.objectNode()
                    .putArray("disabled")).awaitStartup();
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(scripted.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8));
            root.plugin(new CliPlugin(in, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                    tempDir.resolve("sessions"), fixedReply("好的")), JsonNodeFactory.instance.objectNode())
                    .awaitStartup();
            // awaitStartup 只等 apply 返回（REPL 线程异步跑脚本）——轮询等 idle 收尾行
            // （CliPluginTest.awaitIdle 同款，最多 10 秒）
            long deadline = System.currentTimeMillis() + 10_000;
            while (!outBuf.toString(StandardCharsets.UTF_8).contains("=== 对话结束 ===")
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
        } finally {
            root.dispose();
        }
        String output = outBuf.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("好的"), "无 workspace 时聊天照常工作: " + output);
        assertTrue(output.contains("workspace 服务未挂载"), "/permission 应降级提示未挂载: " + output);
    }

    /** 固定回复的 mock LLM（CliPluginTest.fixedReply 同款最小形态）。 */
    private static LlmAdapter fixedReply(String reply) {
        return new LlmAdapter() {
            @Override
            public void stream(ChatRequest request, java.util.function.Consumer<ChatChunk> onChunk) {
                onChunk.accept(new ChatChunk(reply));
            }

            @Override
            public LlmTurn streamTurn(ChatRequest request, java.util.function.Consumer<String> textSink) {
                textSink.accept(reply);
                return new LlmTurn(reply, List.of());
            }
        };
    }
}
