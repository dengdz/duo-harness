package dev.duo.harness.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能注册表用例（M7 新 seam）：四根优先级覆盖（同名先到先得）、双形态解析
 * （目录 SKILL.md + 单文件）、禁用表整名跳过、缺失根静默跳过、清单片段。
 */
class SkillRegistryTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：SkillRegistryTest —— 技能注册表：四根优先级覆盖、双形态解析、"
                + "禁用表、缺失根跳过、清单片段（5 用例） ===");
    }

    @TempDir
    Path tempDir;

    /** 在目录下写一个目录包技能：<name>/SKILL.md。 */
    private void writePackage(Path root, String name, String description, String content) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n" + content);
    }

    /** 在目录下写一个单文件技能：<name>.md（无 frontmatter，名取文件名）。 */
    private void writeSingleFile(Path root, String name, String content) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve(name + ".md"), content);
    }

    @Test
    void higherPriorityRootOverridesSameName() throws IOException {
        Path high = tempDir.resolve("high");
        Path low = tempDir.resolve("low");
        writePackage(high, "alpha", "高优先描述", "高优先内容");
        writePackage(low, "alpha", "低优先描述", "低优先内容");
        writePackage(low, "beta", "仅低优先", "低优先独有");

        SkillRegistry registry = SkillRegistry.scan(List.of(high, low), Set.of());

        assertEquals(2, registry.all().size(), "同名覆盖后只剩 alpha + beta");
        assertEquals("高优先描述", registry.find("alpha").description(), "同名高优先根胜出");
        assertEquals("高优先内容", registry.find("alpha").content());
        assertNotNull(registry.find("beta"), "低优先根独有技能正常加载");
    }

    @Test
    void bothPackageAndSingleFileFormsAreParsed() throws IOException {
        Path root = tempDir.resolve("root");
        writePackage(root, "packaged", "目录包技能", "目录包指令");
        writeSingleFile(root, "loose", "单文件指令全文");

        SkillRegistry registry = SkillRegistry.scan(List.of(root), Set.of());

        assertEquals(2, registry.all().size());
        assertEquals("目录包技能", registry.find("packaged").description());
        assertEquals("目录包指令", registry.find("packaged").content());
        assertEquals("单文件指令全文", registry.find("loose").content(), "单文件名取主干、正文即指令");
    }

    @Test
    void disabledSkillsAreSkippedEntirely() throws IOException {
        Path high = tempDir.resolve("high");
        Path low = tempDir.resolve("low");
        writePackage(high, "noisy", "高优先噪声", "x");
        writePackage(low, "noisy", "低优先版本", "y");
        writePackage(low, "wanted", "要的", "z");

        SkillRegistry registry = SkillRegistry.scan(List.of(high, low), Set.of("noisy"));

        assertNull(registry.find("noisy"), "禁用名整名跳过——低优先根的同名也不生效");
        assertEquals("要的", registry.find("wanted").description());
    }

    @Test
    void missingRootsAndInvalidEntriesAreSkipped() throws IOException {
        Path root = tempDir.resolve("root");
        Files.createDirectories(root);
        writePackage(root, "good", "合法技能", "内容");
        // 非法条目：目录缺 SKILL.md、空白正文单文件
        Files.createDirectories(root.resolve("empty-dir"));
        Files.writeString(root.resolve("blank.md"), "   ");

        SkillRegistry registry = SkillRegistry.scan(
                List.of(tempDir.resolve("不存在"), root), Set.of());

        assertEquals(1, registry.all().size(), "缺失根静默跳过、非法条目跳过");
        assertNotNull(registry.find("good"));
    }

    @Test
    void catalogFragmentListsNamesAndDescriptionsOrNullWhenEmpty() throws IOException {
        Path root = tempDir.resolve("root");
        writePackage(root, "release-notes", "按仓库规范生成发布说明", "指令内容");

        SkillRegistry registry = SkillRegistry.scan(List.of(root), Set.of());
        String fragment = registry.catalogFragment();

        assertTrue(fragment.contains("skill 工具"), fragment);
        assertTrue(fragment.contains("release-notes"), fragment);
        assertTrue(fragment.contains("按仓库规范生成发布说明"), fragment);

        SkillRegistry empty = SkillRegistry.scan(List.of(tempDir.resolve("无")), Set.of());
        assertNull(empty.catalogFragment(), "无技能时不注册空片段");
    }
}
