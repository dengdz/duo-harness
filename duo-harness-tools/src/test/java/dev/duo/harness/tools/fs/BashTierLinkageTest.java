package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.tools.Answerer;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionPlugin;
import dev.duo.harness.tools.InteractionRequest;
import dev.duo.harness.tools.InteractionService;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bash 档位联动（M12-03，工具服务级装配）：danger 档短路放行（不打扰回答者）、
 * workspace-write 与 read-only 档一律 ask（拒绝即命令不执行）、非零退出经管线
 * 仍是正常结果而非错误。
 */
class BashTierLinkageTest {

    @TempDir
    Path tempDir;

    private Context root;

    interface ToolsView {

        ToolsService tools();
    }

    interface AnswersView {

        InteractionService answers();
    }

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：BashTierLinkageTest —— bash 档位联动：danger 短路放行 /"
                + "其余档 ask 且拒绝即不执行 / 退出码非错误（4 用例） ===");
    }

    @AfterEach
    void tearDown() {
        if (root != null) {
            root.dispose();
        }
    }

    /** 记录被问过的工具并给固定裁决：区分"档位短路（未被问）"与"走审批（被问）"。 */
    private static final class RecordingAnswerer implements Answerer {

        private final List<String> asked = new ArrayList<>();
        private final boolean allow;

        RecordingAnswerer(boolean allow) {
            this.allow = allow;
        }

        @Override
        public InteractionAnswer answer(InteractionRequest request) {
            asked.add(request.subject());
            return allow ? InteractionAnswer.allow("test") : InteractionAnswer.deny("test");
        }
    }

    /** 装配（档位即被测变量）：tools → interactions → fs（提供 workspace）→ 档位审批。 */
    private RecordingAnswerer assemble(WorkspacePolicy.Mode mode, boolean allow) {
        root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        root.plugin(new InteractionPlugin(), null).awaitStartup();
        root.plugin(new FsToolsPlugin(), JsonNodeFactory.instance.objectNode()
                .put("mode", mode.configName())
                .put("root", tempDir.toString())).awaitStartup();
        root.plugin(new WorkspaceApprovalPlugin(), null).awaitStartup();
        RecordingAnswerer answerer = new RecordingAnswerer(allow);
        root.as(AnswersView.class).answers().register(root, answerer);
        return answerer;
    }

    private static JsonNode args(String json) {
        try { return new ObjectMapper().readTree(json); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private ToolResult runBash(String command) {
        return root.as(ToolsView.class).tools().execute("bash",
                args("{\"command\":\"" + command + "\"}"));
    }

    @Test
    void dangerRunsBashWithoutAsking() {
        RecordingAnswerer answerer = assemble(WorkspacePolicy.Mode.DANGER_FULL_ACCESS, false);

        ToolResult result = runBash("echo 危险档直通");

        assertFalse(result.isError(), String.valueOf(result.value()));
        assertTrue(String.valueOf(result.value()).contains("危险档直通"), String.valueOf(result.value()));
        assertTrue(answerer.asked.isEmpty(), "danger 档短路——回答者不应被咨询");
    }

    @Test
    void workspaceWriteAsksAndDenialGatesExecution() throws Exception {
        RecordingAnswerer answerer = assemble(WorkspacePolicy.Mode.WORKSPACE_WRITE, false);

        ToolResult result = runBash("touch 未获批准.txt");

        assertEquals(List.of("bash"), answerer.asked, "workspace-write 档 bash 无路径参数——一律 ask");
        assertTrue(result.isError(), "被人拒绝即错误结果: " + result.value());
        assertTrue(String.valueOf(result.value()).contains("被人拒绝"), String.valueOf(result.value()));
        Thread.sleep(200); // 拒绝路径本就不同步执行；留出窗口让"万一执行"的写入可见
        assertFalse(Files.exists(tempDir.resolve("未获批准.txt")), "被拒的命令未执行");
    }

    @Test
    void readOnlyAsksAndApprovalRunsCommand() {
        RecordingAnswerer answerer = assemble(WorkspacePolicy.Mode.READ_ONLY, true);

        ToolResult result = runBash("echo 读档已批准");

        assertEquals(List.of("bash"), answerer.asked, "read-only 档 bash 一律 ask");
        assertFalse(result.isError(), String.valueOf(result.value()));
        assertTrue(String.valueOf(result.value()).contains("读档已批准"), String.valueOf(result.value()));
    }

    @Test
    void nonZeroExitStaysNormalResultThroughPipeline() {
        assemble(WorkspacePolicy.Mode.DANGER_FULL_ACCESS, false);

        ToolResult result = runBash("echo 有输出; exit 7");

        assertFalse(result.isError(), "非零退出不是错误: " + result.value());
        assertTrue(String.valueOf(result.value()).contains("[exit code: 7]"), String.valueOf(result.value()));
    }
}