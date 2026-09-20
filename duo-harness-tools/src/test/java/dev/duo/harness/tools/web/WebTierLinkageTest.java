package dev.duo.harness.tools.web;

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
import dev.duo.harness.tools.fs.FsToolsPlugin;
import dev.duo.harness.tools.fs.WorkspaceApprovalPlugin;
import dev.duo.harness.tools.fs.WorkspacePolicy;
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
 * 网络读档位联动（M20 工单 06，工具服务级装配，先例 BashTierLinkageTest）：
 * read-only 档 web_fetch 声明需审批、经档位闸门 ask（拒绝即不执行——零网络请求）；
 * workspace-write 与 danger 档不声明（未被问，直接进工具本体——以 guard 拒绝回环
 * 目标为"已执行到工具"的证词）。MockWebServer 供靶，零真实外网。
 */
class WebTierLinkageTest {

    @TempDir
    Path tempDir;

    private Context root;
    private MockWebServer server;

    interface ToolsView {

        ToolsService tools();
    }

    interface AnswersView {

        InteractionService answers();
    }

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：WebTierLinkageTest —— 网络读档位联动：read-only ask 且拒绝即不执行 /"
                + "其余档不声明直通（4 用例） ===");
    }

    @AfterEach
    void tearDown() {
        if (root != null) {
            root.dispose();
        }
        if (server != null) {
            server.stop();
        }
    }

    /** 记录被问过的工具并给固定裁决：区分"档位未声明（未被问）"与"走审批（被问）"。 */
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

    /** 装配（档位即被测变量）：tools → interactions → fs（提供 workspace）→ 档位审批 → web 工具。 */
    private RecordingAnswerer assemble(WorkspacePolicy.Mode mode, boolean allow) throws Exception {
        server = new MockWebServer();
        root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        root.plugin(new InteractionPlugin(), null).awaitStartup();
        root.plugin(new FsToolsPlugin(), JsonNodeFactory.instance.objectNode()
                .put("mode", mode.configName())
                .put("root", tempDir.toString())).awaitStartup();
        root.plugin(new WorkspaceApprovalPlugin(), null).awaitStartup();
        root.plugin(new WebToolsPlugin(), JsonNodeFactory.instance.objectNode()).awaitStartup();
        RecordingAnswerer answerer = new RecordingAnswerer(allow);
        root.as(AnswersView.class).answers().register(root, answerer);
        return answerer;
    }

    private ToolResult runFetch() {
        return root.as(ToolsView.class).tools().execute("web_fetch",
                WebToolsConfigTestSupport.args("{\"url\":\"" + server.baseUrl() + "/x\"}"));
    }

    @Test
    void readOnlyAsksAndDenialGatesExecution() throws Exception {
        RecordingAnswerer answerer = assemble(WorkspacePolicy.Mode.READ_ONLY, false);

        ToolResult result = runFetch();

        assertEquals(List.of("web_fetch"), answerer.asked, "read-only 档联网一律 ask（ADR-0021 决策 8）");
        assertTrue(result.isError(), "被人拒绝即错误结果: " + result.value());
        assertTrue(String.valueOf(result.value()).contains("被人拒绝"), String.valueOf(result.value()));
        assertEquals(0, server.requestCount(), "被拒的抓取未发出任何网络请求");
    }

    @Test
    void readOnlyApprovalLetsFetchProceedToToolBody() throws Exception {
        RecordingAnswerer answerer = assemble(WorkspacePolicy.Mode.READ_ONLY, true);

        ToolResult result = runFetch();

        assertEquals(List.of("web_fetch"), answerer.asked);
        // 批准后进入工具本体——目标在回环上被 guard 拒绝（此即"已执行到工具"的证词）
        assertFalse(result.isError(), String.valueOf(result.value()));
        assertTrue(String.valueOf(result.value()).contains("非公网"), String.valueOf(result.value()));
    }

    @Test
    void workspaceWriteDoesNotDeclareAndRunsDirectly() throws Exception {
        RecordingAnswerer answerer = assemble(WorkspacePolicy.Mode.WORKSPACE_WRITE, false);

        ToolResult result = runFetch();

        assertTrue(answerer.asked.isEmpty(), "默认档不声明审批——回答者不应被咨询");
        assertFalse(result.isError(), String.valueOf(result.value()));
        assertTrue(String.valueOf(result.value()).contains("非公网"), "直接执行到工具本体（guard 拒回环）: " + result.value());
    }

    @Test
    void dangerShortCircuitsToo() throws Exception {
        RecordingAnswerer answerer = assemble(WorkspacePolicy.Mode.DANGER_FULL_ACCESS, false);

        ToolResult result = runFetch();

        assertTrue(answerer.asked.isEmpty(), "danger 档短路");
        assertTrue(String.valueOf(result.value()).contains("非公网"), String.valueOf(result.value()));
    }
}
