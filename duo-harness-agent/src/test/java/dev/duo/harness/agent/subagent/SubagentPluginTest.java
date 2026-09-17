package dev.duo.harness.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * subagent 插件装配用例（工单 02/05）：条件注册——未配置模板零注册（工具清单与
 * 0.9.0 一致，升级零感知）；依赖反转全链（宿主发布 → 插件读取 → 五件工具装配）；
 * 宿主缺失挂起（不失败、零工具）。
 */
class SubagentPluginTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SubagentPluginTest —— 条件注册：未配置模板零注册（零变化）、"
                + "供给清单全注册、宿主驱动五件装配（3 用例；宿主缺失的依赖挂起属内核语义，"
                + "由 LifecycleTest 覆盖） ===");
    }

    /** tools 服务的视图接口（方法名即服务名）。 */
    interface ToolsView {

        ToolsService tools();
    }

    private Context root;

    @AfterEach
    void tearDown() {
        if (root != null) {
            root.dispose();
        }
    }

    private ToolsService startToolsDomain() {
        root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        // 假宿主（SubagentPlugin 声明 inject subagent-host——子代理必须有父 agent
        // 运行环境；生产由呈现位装配时发布）
        root.provide(dev.duo.harness.agent.subagent.SubagentHost.SERVICE_NAME,
                new dev.duo.harness.agent.subagent.SubagentHost(notUsedLlm(), null, () -> null));
        return root.as(ToolsView.class).tools();
    }

    private static JsonNode config(String json) throws Exception {
        return JSON.readTree(json);
    }

    /** fake 工具（机制锁定用；本体与执行语义归 03/04）。 */
    private static ToolDefinition fakeTool(String name) {
        return new ToolDefinition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "fake: " + name;
            }

            @Override
            public JsonNode parameters() {
                return JSON.createObjectNode();
            }

            @Override
            public Object execute(dev.duo.harness.tools.ToolExecution execution) {
                return "ok";
            }
        };
    }

    @Test
    void withoutTemplatesRegistersNothing() throws Exception {
        // 未配置模板部署零变化：五件工具一个都不出现（spec 用户故事 14/20）
        ToolsService tools = startToolsDomain();
        List<String> before = tools.list().stream().map(ToolDefinition::name).toList();

        root.plugin(new SubagentPlugin(), config("{}")).awaitStartup();
        root.plugin(new SubagentPlugin(), config("{\"templates\":null}")).awaitStartup();

        List<String> after = tools.list().stream().map(ToolDefinition::name).toList();
        assertEquals(before, after, "工具清单与装配前完全一致");
        assertTrue(after.stream().noneMatch(Set.of(
                        "spawn", "fork", "send_message", "interrupt_agent", "list_agents")::contains),
                "五件工具零出现: " + after);
    }

    @Test
    void conditionalRegistrationRegistersAllSupplied() throws Exception {
        ToolsService tools = startToolsDomain();
        SubagentTemplates templates = SubagentTemplates.parse(config(
                "{\"templates\": [{\"name\": \"r\", \"tools\": [\"fs_read\"]}]}"));
        List<BiFunction<SubagentManager, Supplier<Session>, ToolDefinition>> suppliers = List.of(
                (m, s) -> fakeTool("send_message"), (m, s) -> fakeTool("list_agents"));

        SubagentPlugin.registerAgentTools(root, tools, new SubagentManager(templates),
                () -> null, suppliers);

        List<String> names = tools.list().stream().map(ToolDefinition::name).toList();
        assertTrue(names.contains("send_message") && names.contains("list_agents"),
                "供给清单全部注册: " + names);
    }

    @Test
    void templatePresentRegistersFiveToolsViaHost() throws Exception {
        // 依赖反转后的全链：宿主发布 → 插件 inject 读取 → 五件工具装配（M15 工单 05 前提）
        ToolsService tools = startToolsDomain();

        root.plugin(new SubagentPlugin(), config(
                "{\"templates\": [{\"name\": \"r\", \"tools\": [\"fs_read\"]}]}")).awaitStartup();

        List<String> names = tools.list().stream().map(ToolDefinition::name).toList();
        assertTrue(names.containsAll(List.of("spawn", "fork", "send_message",
                "interrupt_agent", "list_agents")), "五件工具齐备: " + names);
    }

    /** 占位适配器：宿主驱动用例中不真正调用模型。 */
    private static dev.duo.harness.llm.LlmAdapter notUsedLlm() {
        return new dev.duo.harness.llm.LlmAdapter() {
            @Override
            public void stream(dev.duo.harness.llm.ChatRequest request,
                               java.util.function.Consumer<dev.duo.harness.llm.ChatChunk> onChunk) {
                onChunk.accept(new dev.duo.harness.llm.ChatChunk("直答"));
            }

            @Override
            public dev.duo.harness.llm.LlmTurn streamTurn(
                    dev.duo.harness.llm.ChatRequest request,
                    java.util.function.Consumer<String> textSink) {
                textSink.accept("直答");
                return new dev.duo.harness.llm.LlmTurn("直答", List.of());
            }
        };
    }
}
