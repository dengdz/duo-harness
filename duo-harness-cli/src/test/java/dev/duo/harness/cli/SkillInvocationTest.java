package dev.duo.harness.cli;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.agent.Skill;
import dev.duo.harness.agent.SkillRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 技能直调识别用例（M11-02）：/技能名 前缀注入指令全文、未知名返回 null、
 * 非斜杠输入原样透传。
 */
class SkillInvocationTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SkillInvocationTest —— CLI 技能直调识别：前缀注入、"
                + "未知名提示、非斜杠透传（1 用例） ===");
    }

    @TempDir
    Path fixture;

    @Test
    void skillInvocationPrefixInjectsInstructions() throws IOException {
        Files.createDirectories(fixture.resolve("release-notes"));
        Files.writeString(fixture.resolve("release-notes").resolve("SKILL.md"),
                "---\nname: release-notes\ndescription: 生成发布说明\n---\n请按仓库规范撰写发布说明。");
        SkillRegistry registry = SkillRegistry.scan(List.of(fixture), java.util.Set.of());

        // 命中：指令全文 + 用户输入
        assertEquals("请按仓库规范撰写发布说明。\n\n用户输入：0.3.0",
                CliPlugin.resolveSkillInvocation("/release-notes 0.3.0", registry));
        // 命中：无其余输入 → 仅指令全文
        assertEquals("请按仓库规范撰写发布说明。",
                CliPlugin.resolveSkillInvocation("/release-notes", registry));
        // 未知名 → null（REPL 提示未知命令）
        assertNull(CliPlugin.resolveSkillInvocation("/不存在", registry));
        // 非斜杠输入原样透传
        assertEquals("普通问题", CliPlugin.resolveSkillInvocation("普通问题", registry));
    }
}
