package dev.duo.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsPlugin;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * skill 工具用例（工具服务级装配）：按名返回指令全文 + 遵循提示、未知名收敛为
 * error 结果点名可用技能、缺 name 点名。
 */
class SkillToolTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SkillToolTest —— skill 工具：按名返回指令全文、"
                + "未知名点名可用清单、缺 name 点名（3 用例） ===");
    }

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

    /** 用扫描夹具构造注册表：临时根 + 目录包技能。 */
    private SkillRegistry registryWith(@TempDir Path fixture, Skill... skills) throws IOException {
        for (Skill skill : skills) {
            Path dir = fixture.resolve(skill.name());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"),
                    "---\nname: " + skill.name() + "\ndescription: " + skill.description()
                            + "\n---\n" + skill.content());
        }
        return SkillRegistry.scan(List.of(fixture), Set.of());
    }

    @Test
    void knownSkillReturnsContentWithFollowHint(@TempDir Path fixture) throws IOException {
        SkillRegistry registry = registryWith(fixture,
                new Skill("release-notes", "生成发布说明", "请按仓库规范撰写发布说明。"));
        root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        ToolsService tools = root.as(ToolsView.class).tools();
        tools.register(root, new SkillTool(registry));

        ToolResult result = tools.execute(SkillTool.NAME,
                args("{\"name\": \"release-notes\"}"));

        assertFalse(result.isError());
        String text = String.valueOf(result.value());
        assertTrue(text.contains("请按仓库规范撰写发布说明。"), text);
        assertTrue(text.contains("请遵循以上技能指令"), text);
    }

    @Test
    void unknownNameConvergesToErrorListingAvailable(@TempDir Path fixture) throws IOException {
        SkillRegistry registry = registryWith(fixture, new Skill("alpha", "A", "内容A"));
        root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        ToolsService tools = root.as(ToolsView.class).tools();
        tools.register(root, new SkillTool(registry));

        ToolResult result = tools.execute(SkillTool.NAME, args("{\"name\": \"nope\"}"));

        assertTrue(result.isError());
        String text = String.valueOf(result.value());
        assertTrue(text.contains("未知技能: nope"), text);
        assertTrue(text.contains("alpha"), "错误结果点名可用技能: " + text);
    }

    @Test
    void missingNameConvergesToErrorResult(@TempDir Path fixture) throws IOException {
        SkillRegistry registry = registryWith(fixture);
        root = Context.root();
        root.plugin(new ToolsPlugin(), null).awaitStartup();
        ToolsService tools = root.as(ToolsView.class).tools();
        tools.register(root, new SkillTool(registry));

        ToolResult result = tools.execute(SkillTool.NAME, args("{}"));

        assertTrue(result.isError());
        assertTrue(String.valueOf(result.value()).contains("name"), String.valueOf(result.value()));
    }

    private static JsonNode args(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
