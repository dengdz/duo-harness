package dev.duo.harness.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.PluginException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子代理模板集用例（工单 02）：config 严格绑定解析（缺席回退空集 / 正常保序 /
 * 未知字段与类型错点名）与强制过滤（交互/控制面工具配了也滤除——治理底线
 * 不可绕过，ADR-0015 决策 5/6）。
 */
class SubagentTemplatesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SubagentTemplatesTest —— 模板解析：缺席回退/保序/严格绑定点名、"
                + "强制过滤（交互与控制面工具不可绕过）、按名查寻、迭代上限解析与缺省（13 用例） ===");
    }

    private static JsonNode config(String json) throws Exception {
        return JSON.readTree(json);
    }

    // ===== 缺席回退 =====

    @Test
    void absentConfigYieldsEmptyRegistry() throws Exception {
        assertTrue(SubagentTemplates.parse(null).isEmpty(), "config 缺失");
        assertTrue(SubagentTemplates.parse(config("{}")).isEmpty(), "无 templates 段");
        assertTrue(SubagentTemplates.parse(config("{\"templates\":null}")).isEmpty(), "段为 null");
    }

    // ===== 正常解析 =====

    @Test
    void parsesTemplatesInDeclaredOrder() throws Exception {
        SubagentTemplates templates = SubagentTemplates.parse(config("""
                {"templates": [
                  {"name": "researcher", "tools": ["fs_read", "web_search"],
                   "prompt": "你是调研助手"},
                  {"name": "worker", "tools": ["fs_read", "fs_write"]}
                ]}"""));
        assertEquals(2, templates.all().size(), "模板按声明顺序解析");
        assertEquals("researcher", templates.all().get(0).name());
        assertEquals(List.of("fs_read", "web_search"), templates.all().get(0).tools(), "工具清单保序");
        assertEquals("你是调研助手", templates.all().get(0).prompt(), "专属提示解析");
        assertEquals("worker", templates.all().get(1).name());
        assertEquals(null, templates.all().get(1).prompt(), "prompt 可省");
        assertEquals("worker", templates.byName("worker").orElseThrow().name(), "按名查寻命中");
        assertTrue(templates.byName("ghost").isEmpty(), "未知名返回 empty");
    }

    // ===== 严格绑定：结构错误一律点名 =====

    @Test
    void rejectsUnknownFieldInTemplate() throws Exception {
        PluginException e = assertThrows(PluginException.class,
                () -> SubagentTemplates.parse(config(
                        "{\"templates\": [{\"name\": \"r\", \"tools\": [\"fs_read\"], \"model\": \"x\"}]}")));
        assertTrue(e.getMessage().contains("model"), "未知字段点名: " + e.getMessage());
    }

    @Test
    void rejectsMalformedTemplatesSection() throws Exception {
        assertThrows(PluginException.class,
                () -> SubagentTemplates.parse(config("{\"templates\": {}}")), "段非数组");
        assertThrows(PluginException.class,
                () -> SubagentTemplates.parse(config("{\"templates\": [\"researcher\"]}")), "项非对象");
    }

    @Test
    void rejectsMissingOrBlankName() throws Exception {
        assertThrows(PluginException.class,
                () -> SubagentTemplates.parse(config("{\"templates\": [{\"tools\": [\"fs_read\"]}]}")), "缺 name");
        assertThrows(PluginException.class,
                () -> SubagentTemplates.parse(config(
                        "{\"templates\": [{\"name\": \" \", \"tools\": [\"fs_read\"]}]}")), "name 空白");
    }

    @Test
    void rejectsDuplicateName() throws Exception {
        PluginException e = assertThrows(PluginException.class,
                () -> SubagentTemplates.parse(config("""
                        {"templates": [
                          {"name": "r", "tools": ["fs_read"]},
                          {"name": "r", "tools": ["fs_write"]}
                        ]}""")));
        assertTrue(e.getMessage().contains("r"), "重复模板名点名: " + e.getMessage());
    }

    @Test
    void rejectsEmptyOrMalformedTools() throws Exception {
        assertThrows(PluginException.class,
                () -> SubagentTemplates.parse(config("{\"templates\": [{\"name\": \"r\"}]}")), "缺 tools");
        assertThrows(PluginException.class,
                () -> SubagentTemplates.parse(config(
                        "{\"templates\": [{\"name\": \"r\", \"tools\": []}]}")), "tools 空数组");
        assertThrows(PluginException.class,
                () -> SubagentTemplates.parse(config(
                        "{\"templates\": [{\"name\": \"r\", \"tools\": [\"fs_read\", 3]}]}")), "工具项非文本");
    }

    @Test
    void rejectsNonTextPrompt() throws Exception {
        assertThrows(PluginException.class,
                () -> SubagentTemplates.parse(config(
                        "{\"templates\": [{\"name\": \"r\", \"tools\": [\"fs_read\"], \"prompt\": 1}]}")));
    }

    @Test
    void parsesMaxIterationsWithDefaultFallback() throws Exception {
        SubagentTemplates templates = SubagentTemplates.parse(config("""
                {"templates": [
                  {"name": "deep", "tools": ["read"], "maxIterations": 40},
                  {"name": "plain", "tools": ["read"]}
                ]}"""));
        assertEquals(40, templates.byName("deep").orElseThrow().effectiveMaxIterations(),
                "配置值生效");
        assertEquals(SubagentTemplate.DEFAULT_MAX_ITERATIONS,
                templates.byName("plain").orElseThrow().effectiveMaxIterations(),
                "未配置走缺省（子代理重活默认 30）");
    }

    @Test
    void rejectsNonPositiveMaxIterations() throws Exception {
        PluginException e = assertThrows(PluginException.class,
                () -> SubagentTemplates.parse(config(
                        "{\"templates\": [{\"name\": \"r\", \"tools\": [\"read\"], \"maxIterations\": 0}]}")));
        assertTrue(e.getMessage().contains("maxIterations"), "越界点名: " + e.getMessage());
        assertThrows(PluginException.class,
                () -> SubagentTemplates.parse(config(
                        "{\"templates\": [{\"name\": \"r\", \"tools\": [\"read\"], \"maxIterations\": \"多\"}]}")));
    }

    // ===== 强制过滤：治理底线不可绕过 =====

    @Test
    void allowedToolsDropsForbiddenEvenWhenConfigured() {
        // 模板配了交互工具与控制面五件：一律滤除——配置合法但能力边界由框架强制
        SubagentTemplate template = new SubagentTemplate("r",
                List.of("fs_read", "ask_user", "exit_plan_mode", "spawn", "fork",
                        "send_message", "interrupt_agent", "list_agents"));
        Set<String> registered = Set.of("fs_read", "ask_user", "exit_plan_mode",
                "spawn", "fork", "send_message", "interrupt_agent", "list_agents");

        List<String> allowed = template.allowedTools(registered);

        assertEquals(List.of("fs_read"), allowed, "禁用工具全部滤除，仅剩普通工具");
    }

    @Test
    void allowedToolsKeepsOrderAndDropsUnregistered() {
        SubagentTemplate template = new SubagentTemplate("r",
                List.of("web_search", "ghost_tool", "fs_read"));
        Set<String> registered = Set.of("fs_read", "web_search");

        List<String> allowed = template.allowedTools(registered);

        assertEquals(List.of("web_search", "fs_read"), allowed, "保声明顺序，未注册工具不进集");
    }

    @Test
    void allowedToolsDeduplicatesTemplateList() {
        SubagentTemplate template = new SubagentTemplate("r",
                List.of("fs_read", "fs_read"));
        Set<String> registered = Set.of("fs_read");

        assertEquals(List.of("fs_read"), template.allowedTools(registered), "重复声明去重");
    }
}
