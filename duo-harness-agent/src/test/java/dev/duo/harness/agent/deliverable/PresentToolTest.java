package dev.duo.harness.agent.deliverable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * present 用例（M26 工单 04，ADR-0028）：存在性校验（相对/绝对路径）、失败逐条
 * 点名且不落事件、去重保序、1-8 上限、事件往返与可检索性前提、查重注册。
 */
class PresentToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：PresentToolTest —— 交付声明：存在性校验与逐条点名、去重保序、上限、事件落盘与往返（8 用例） ===");
    }

    @TempDir
    Path tempDir;

    private Path cwd;
    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        cwd = tempDir.resolve("proj");
        Files.createDirectories(cwd);
        Files.writeString(cwd.resolve("report.md"), "成果正文");
        Files.createDirectories(cwd.resolve("out"));
        Files.writeString(cwd.resolve("out/data.json"), "{}");
        session = Session.create(tempDir.resolve("sessions"), cwd);
    }

    private String execute(PresentTool tool, String filesJson) throws Exception {
        return (String) tool.execute(new ToolExecution(PresentTool.NAME,
                JSON.readTree("{\"files\":" + filesJson + "}"), null));
    }

    @Test
    void validDeclarationAppendsEventWithAbsolutePaths() throws Exception {
        PresentTool tool = new PresentTool(() -> session, cwd);
        String out = execute(tool, "[\"report.md\", \"" + cwd + "/out/data.json\"]");

        assertTrue(out.contains("交付声明已记录（2 个文件）"), out);
        assertEquals(1, session.events().size());
        SessionEvent event = session.events().get(0);
        assertEquals(SessionEvent.DELIVERABLE_PRESENTED, event.type());
        JsonNode payload = JSON.readTree(event.text());
        assertEquals(2, payload.size());
        // 相对路径按 cwd 解析为绝对形态落盘（两入口/导出/检索同源可解析）
        assertEquals(cwd.resolve("report.md").toString(), payload.get(0).asText());
    }

    @Test
    void missingFilesRejectedByNameAndNoEvent() throws Exception {
        PresentTool tool = new PresentTool(() -> session, cwd);
        String out = execute(tool, "[\"report.md\", \"ghost.txt\", \"also-missing.log\"]");

        assertTrue(out.contains("[present 错误]"), out);
        assertTrue(out.contains("ghost.txt"), "失败文件逐条点名: " + out);
        assertTrue(out.contains("also-missing.log"), out);
        assertTrue(session.events().isEmpty(), "校验未通过不落事件（宁缺毋假）");
    }

    @Test
    void duplicatesDedupedPreservingOrder() throws Exception {
        PresentTool tool = new PresentTool(() -> session, cwd);
        String out = execute(tool, "[\"report.md\", \"./report.md\", \"out/data.json\"]");

        assertTrue(out.contains("交付声明已记录（2 个文件）"), "同文件两种写法去重: " + out);
        JsonNode payload = JSON.readTree(session.events().get(0).text());
        assertEquals(2, payload.size());
    }

    @Test
    void overLimitRejected() throws Exception {
        PresentTool tool = new PresentTool(() -> session, cwd);
        StringBuilder nine = new StringBuilder("[");
        for (int i = 0; i < 9; i++) {
            if (i > 0) {
                nine.append(',');
            }
            Files.writeString(cwd.resolve("f" + i + ".txt"), "x");
            nine.append("\"f").append(i).append(".txt\"");
        }
        nine.append(']');

        String out = execute(tool, nine.toString());
        assertTrue(out.contains("单次最多声明 8 个文件"), out);
        assertTrue(session.events().isEmpty());
    }

    @Test
    void emptyOrMissingSessionRejected() throws Exception {
        PresentTool orphan = new PresentTool(() -> null, cwd);
        assertTrue(execute(orphan, "[\"report.md\"]").contains("归属会话"));
        PresentTool tool = new PresentTool(() -> session, cwd);
        assertTrue(execute(tool, "[]").contains("非空数组"));
        assertTrue(execute(tool, "\"report.md\"").contains("非空数组"), "非数组形态拒绝");
        assertTrue(session.events().isEmpty());
    }

    @Test
    void eventRoundTripsThroughDisk() throws Exception {
        PresentTool tool = new PresentTool(() -> session, cwd);
        execute(tool, "[\"out/data.json\"]");
        session.close();

        Session reloaded = Session.load(tempDir.resolve("sessions")
                .resolve(session.id() + ".jsonl"));
        assertEquals(1, reloaded.events().size());
        assertEquals(SessionEvent.DELIVERABLE_PRESENTED, reloaded.events().get(0).type());
        assertTrue(reloaded.events().get(0).text().contains("data.json"));
        reloaded.close();
    }

    @Test
    void declarationNotProjectedAsMessage() throws Exception {
        PresentTool tool = new PresentTool(() -> session, cwd);
        execute(tool, "[\"report.md\"]");
        assertTrue(session.deriveMessages().isEmpty(),
                "交付声明是元数据不是对话消息（投影跳过）");
    }

    @Test
    void registrationIsAbsentSafe() throws Exception {
        // 查重先到先得（与 todo/交互工具同模式）：重放注册不产生第二实例
        dev.duo.harness.core.api.Context root = dev.duo.harness.core.api.Context.root();
        root.plugin(new dev.duo.harness.tools.ToolsPlugin(), null).awaitStartup();
        var tools = root.as(ToolsView.class).tools();
        dev.duo.harness.agent.presenter.PresenterAssembly.registerPresentTool(
                root, tools, () -> session, cwd);
        dev.duo.harness.agent.presenter.PresenterAssembly.registerPresentTool(
                root, tools, () -> session, cwd);
        long count = tools.list().stream().filter(d -> PresentTool.NAME.equals(d.name())).count();
        assertEquals(1, count, "同名工具只注册一次");
        root.dispose();
    }

    /** tools 服务的视图接口（方法名即服务名，与 todo 测试同形态）。 */
    interface ToolsView {

        dev.duo.harness.tools.ToolsService tools();
    }
}
