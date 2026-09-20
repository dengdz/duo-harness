package dev.duo.harness.sessionquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * session_search 工具与 session-query 插件装配（工单 08 验收 seam 7）：
 * 渲染面经真实索引（JSONL 夹具），装配面经真实内核容器。
 */
class SessionSearchToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private Context root;

    @BeforeEach
    void mountDomain() {
        root = Context.root();
    }

    @AfterEach
    void unmount() throws Exception {
        root.dispose();
    }

    private SessionSearchTool toolOnFixture() throws Exception {
        Path jsonl = dir.resolve("20260919-100000-0001.jsonl");
        Files.write(jsonl, List.of(
                "{\"type\":\"user/message\",\"at\":1,\"text\":\"苹果的讨论\"}",
                "{\"type\":\"assistant/message\",\"at\":2,\"text\":\"梨的讨论\"}"),
                StandardCharsets.UTF_8);
        return new SessionSearchTool(new InvertedSessionIndex(dir), 8);
    }

    private static String execute(SessionSearchTool tool, String query) throws Exception {
        return (String) tool.execute(new ToolExecution(tool.name(),
                JSON.createObjectNode().put("query", query), null));
    }

    @Test
    void rendersHitsWithSessionAndSnippet() throws Exception {
        SessionSearchTool tool = toolOnFixture();
        String out = execute(tool, "苹果");
        assertTrue(out.contains("Session search: 苹果"));
        assertTrue(out.contains("20260919-100000-0001"));
        assertTrue(out.contains("【苹果】"));
    }

    @Test
    void emptyQueryIsErrorResult() throws Exception {
        SessionSearchTool tool = toolOnFixture();
        assertTrue(execute(tool, "  ").contains("[session_search 错误]"));
    }

    @Test
    void noResultsIsPlain() throws Exception {
        SessionSearchTool tool = toolOnFixture();
        assertEquals("No results found.", execute(tool, "不存在的词香蕉"));
    }

    // ---- 插件装配（真实容器） ----

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface ToolsView {

        ToolsService tools();
    }

    /** session-query 服务的视图接口（方法名即服务名）。 */
    interface SessionQueryView {

        SessionQueryService sessionQuery();
    }

    @Test
    void pluginPublishesServiceAndRegistersTool() throws Exception {
        root.plugin(new dev.duo.harness.tools.ToolsPlugin(), null).awaitStartup();
        ObjectNode config = JSON.createObjectNode().put("sessionsDir", dir.toString());
        root.plugin(new SessionQueryPlugin(), config).awaitStartup();

        assertTrue(root.as(SessionQueryView.class).sessionQuery()
                .search("苹果", 8).isEmpty()); // 服务可用（空目录空结果）
        assertTrue(root.as(ToolsView.class).tools().list().stream()
                .anyMatch(t -> SessionSearchTool.NAME.equals(t.name()))); // tools 在册 → 工具注册
    }

    @Test
    void pluginWithoutToolsDomainStillPublishesService() {
        ObjectNode config = JSON.createObjectNode().put("sessionsDir", dir.toString());
        root.plugin(new SessionQueryPlugin(), config).awaitStartup();
        assertTrue(root.hasService(SessionQueryService.SERVICE_NAME));
    }

    @Test
    void invalidMaxResultsFailsStartupLoud() {
        root.plugin(new dev.duo.harness.tools.ToolsPlugin(), null).awaitStartup();
        ObjectNode config = JSON.createObjectNode()
                .put("sessionsDir", dir.toString()).put("maxResults", 0);
        assertThrows(PluginException.class,
                () -> root.plugin(new SessionQueryPlugin(), config).awaitStartup());
    }

    @Test
    void defaultMaxResultsIsEight() {
        assertEquals(8, SessionQueryPlugin.parseMaxResults(null));
    }
}
