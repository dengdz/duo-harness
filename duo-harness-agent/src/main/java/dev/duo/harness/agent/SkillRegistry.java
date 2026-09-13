package dev.duo.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.duo.harness.core.api.boot.DuoHome;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 技能注册表（"skills" 服务，M7）：启动时按发现根优先级扫描技能，供清单注入
 * 与按名加载。发现根顺序即优先级（首根最高，同名先到先得）——默认四根：
 * 项目 `.duo/skills` → 项目 `.agents/skills`（行业标准）→ `~/.duo/skills` →
 * `~/.agents/skills`；项目根以 .git 标记定位（无 .git 即 cwd）。
 *
 * <p>双形态：目录包 `{@code <name>/SKILL.md}` 与单文件 {@code <name>.md}，
 * frontmatter 只认 {@code name} / {@code description}（正文即指令全文）；
 * 禁用表命中者不加载。不做热加载——仅启动扫描（M7 定案）。</p>
 */
public final class SkillRegistry {

    /** 服务名（harness 保留裸名）。 */
    public static final String SERVICE_NAME = "skills";

    /** 默认总预算无关——技能正文不限长（加载走按需，不进常驻上下文）。 */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** 已加载技能（名字 → 技能；插入序 = 发现根优先级序）。 */
    private final Map<String, Skill> skills;

    private SkillRegistry(Map<String, Skill> skills) {
        this.skills = skills;
    }

    /**
     * 按发现根优先级扫描（首根最高，同名先到先得；禁用表命中者整名跳过；
     * 目录不存在或条目非法静默跳过——扫描对环境宽容）。
     *
     * @param roots    发现根（按优先级降序排列）
     * @param disabled 禁用的技能名集合
     * @return 已加载的技能注册表
     */
    public static SkillRegistry scan(List<Path> roots, Set<String> disabled) {
        Map<String, Skill> loaded = new LinkedHashMap<>();
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            List<Path> entries;
            try (Stream<Path> stream = Files.list(root)) {
                entries = stream.sorted().toList();
            } catch (IOException e) {
                continue;
            }
            for (Path entry : entries) {
                String fileName = entry.getFileName().toString();
                Skill skill = fileName.endsWith(".md")
                        ? parseSingleFile(fileName, entry)
                        : parseDirectoryPackage(fileName, entry);
                if (skill == null || disabled.contains(skill.name()) || loaded.containsKey(skill.name())) {
                    continue;
                }
                loaded.put(skill.name(), skill);
            }
        }
        return new SkillRegistry(loaded);
    }

    /** 默认发现根（优先级降序）：项目 .duo/skills → 项目 .agents/skills → ~/.duo/skills → ~/.agents/skills。 */
    public static List<Path> defaultRoots() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path projectRoot = findProjectRoot(cwd);
        Path userHome = Path.of(System.getProperty("user.home"));
        List<Path> roots = new ArrayList<>();
        roots.add(projectRoot.resolve(".duo").resolve("skills"));
        roots.add(projectRoot.resolve(".agents").resolve("skills"));
        roots.add(DuoHome.resolve().root().resolve("skills"));
        roots.add(userHome.resolve(".agents").resolve("skills"));
        return List.copyOf(roots);
    }

    /** 从 cwd 向上定位项目根（.git 标记；未找到即 cwd）。 */
    static Path findProjectRoot(Path cwd) {
        Path current = cwd.toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return cwd.toAbsolutePath();
    }

    /** 全部已加载技能（发现根优先级序；同名高优先根胜出者唯一）。 */
    public List<Skill> all() {
        return List.copyOf(skills.values());
    }

    /** 按名查询；未注册返回 null。 */
    public Skill find(String name) {
        return name == null ? null : skills.get(name);
    }

    /**
     * 聚合清单片段内容（注入 prompt 注册表供模型判断何时加载哪个技能）；
     * 无技能时返回 null（不注册空片段）。
     */
    public String catalogFragment() {
        if (skills.isEmpty()) {
            return null;
        }
        StringBuilder catalog = new StringBuilder("可用技能（需要时用 skill 工具按名加载指令后遵循执行）：");
        for (Skill skill : skills.values()) {
            catalog.append("\n- ").append(skill.name());
            if (!skill.description().isBlank()) {
                catalog.append("：").append(skill.description());
            }
        }
        return catalog.toString();
    }

    /** 目录包形态：{name}/SKILL.md；名取 frontmatter 的 name（缺省即目录名）。 */
    private static Skill parseDirectoryPackage(String dirName, Path skillMdPath) {
        Path skillMd = skillMdPath.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) {
            return null;
        }
        Skill parsed = parseMarkdown(dirName, readQuietly(skillMd));
        if (parsed == null) {
            return null;
        }
        return new Skill(parsed.name(), parsed.description(), parsed.content());
    }

    /** 单文件形态：{name}.md；名缺省取文件名主干。 */
    private static Skill parseSingleFile(String fileName, Path path) {
        String stem = fileName.substring(0, fileName.length() - ".md".length());
        return parseMarkdown(stem, readQuietly(path));
    }

    /**
     * 解析技能 markdown：frontmatter（--- 包裹的 YAML，只认 name/description）可选；
     * 正文即指令全文（空白正文视为非法条目跳过）。
     */
    static Skill parseMarkdown(String fallbackName, String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return null;
        }
        String name = fallbackName;
        String description = "";
        String content = markdown;
        String stripped = markdown.stripLeading();
        if (stripped.startsWith("---")) {
            int close = stripped.indexOf("\n---", 3);
            if (close >= 0) {
                String frontmatter = stripped.substring(3, close).strip();
                content = stripped.substring(stripped.indexOf('\n', close + 1) + 1).strip();
                try {
                    JsonNode fm = YAML.readTree(frontmatter);
                    if (fm != null) {
                        if (fm.hasNonNull("name") && !fm.get("name").asText().isBlank()) {
                            name = fm.get("name").asText().strip();
                        }
                        if (fm.hasNonNull("description")) {
                            description = fm.get("description").asText().strip();
                        }
                    }
                } catch (IOException e) {
                    return null;
                }
            }
        }
        if (name.isBlank() || content.isBlank()) {
            return null;
        }
        return new Skill(name, description, content);
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
