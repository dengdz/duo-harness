package dev.duo.harness.agent.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.duo.harness.core.api.Disposable;
import java.nio.file.WatchKey;
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
 * 禁用表命中者不加载。M23 起支持热加载（ADR-0025，grill Q10）：{@link #startWatch}
 * 监视发现根（含根目录创建），变更触发 {@link #refreshFrom} 重扫描——内容有变
 * revision 递增（缓存失效），清单片段按 sha256 digest 去重、变化才重发且只重发
 * 一次；watch 不可用降级为启动扫描 + 日志警告，不阻断发现。</p>
 */
public final class SkillRegistry {

    /** 服务名（harness 保留裸名）。 */
    public static final String SERVICE_NAME = "skills";

    /** 默认总预算无关——技能正文不限长（加载走按需，不进常驻上下文）。 */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SkillRegistry.class);

    /** 已加载技能（名字 → 技能；插入序 = 发现根优先级序）——volatile 整表替换，读者免锁。 */
    private volatile Map<String, Skill> skills;

    /** 内容级变更计数（整表替换即递增——缓存失效信号，M23 工单 09）。 */
    private final java.util.concurrent.atomic.AtomicLong revision =
            new java.util.concurrent.atomic.AtomicLong();

    /** 当前清单片段 sha256 摘要（片段重发去重的判据；空技能为 null）。 */
    private volatile String catalogDigest;

    /** watch 服务工厂（包内可注入——降级路径测试覆写抛UncheckedIOException）。 */
    java.util.function.Supplier<java.nio.file.WatchService> watchFactory = () -> {
        try {
            return java.nio.file.FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    };

    private SkillRegistry(Map<String, Skill> skills) {
        this.skills = skills;
        this.catalogDigest = digestOf(catalogOf(skills));
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
        return new SkillRegistry(scanMap(roots, disabled));
    }

    /** 扫描共用体：发现根优先级序 + 禁用表 + 同名先到先得（启动扫描与热加载重扫共用）。 */
    private static Map<String, Skill> scanMap(List<Path> roots, Set<String> disabled) {
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
        return loaded;
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

    /** 从 cwd 向上定位项目根（.git 标记；未找到即 cwd）——技能发现与 AGENTS.md 注入跨域共用。 */
    public static Path findProjectRoot(Path cwd) {
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
        return catalogOf(skills);
    }

    /** 当前内容级变更计数（整表替换即递增；失效信号预留——当前无生产消费方）。 */
    public long revision() {
        return revision.get();
    }

    /** 当前清单片段 sha256 摘要（hex；无技能为 null；失效信号预留）。 */
    public String catalogDigest() {
        return catalogDigest;
    }

    /**
     * 热加载重扫（M23 工单 09）：按发现根重扫并比对——技能内容有变（增删改）
     * 即整表替换并 revision 递增；清单片段 digest 变化时返回 true（调用方据此
     * 重发片段，digest 未变不重发——变化才重发、只重发一次）。sync 化：watch
     * 线程独占调用，防重入交错。
     */
    public synchronized boolean refreshFrom(List<Path> roots, Set<String> disabled) {
        Map<String, Skill> fresh = scanMap(roots, disabled);
        boolean contentChanged = !fresh.equals(skills);
        if (contentChanged) {
            skills = fresh;
            revision.incrementAndGet();
        }
        String newDigest = digestOf(catalogOf(fresh));
        boolean catalogChanged = !java.util.Objects.equals(newDigest, catalogDigest);
        catalogDigest = newDigest;
        return catalogChanged;
    }

    private static String catalogOf(Map<String, Skill> skills) {
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

    /** sha256 hex 摘要（null 输入 → null；digest 去重的判定基底）。 */
    private static String digestOf(String content) {
        if (content == null) {
            return null;
        }
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 启动发现根 watch（M23 工单 09）：监视各发现根（含未创建根的现存父级——
     * 捕捉根目录创建）与根内直接子项；任何事件去抖合并后触发一次重扫，清单
     * 片段变化即回调 {@code catalogRefresher}（重发判据由 digest 决定）。
     * watch 不可用时降级：日志警告 + 保持启动扫描结果，不阻断技能发现。
     *
     * @return watch 停表（dispose 停线程并释放 watch；降级时为空操作）
     */
    public Disposable startWatch(List<Path> roots, Set<String> disabled, Runnable catalogRefresher) {
        java.nio.file.WatchService watcher;
        try {
            watcher = watchFactory.get();
        } catch (RuntimeException e) {
            log.warn("技能热加载 watch 不可用（{}），降级为启动扫描——技能变更需重启生效", e.toString());
            return () -> { };
        }
        Map<Path, WatchKey> watched = new java.util.concurrent.ConcurrentHashMap<>();
        for (Path root : roots) {
            registerRootAndParent(watcher, root, watched);
        }
        java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean(false);
        // 平台守护线程：watch 循环是长生命周期阻塞任务（take 无限期等待），且
        // PollingWatchService 内部含 synchronized 段——按 ADR-0002 对虚拟线程的
        // 保守隔离裁定，此类任务用平台线程，防并发高载下 carrier 饥饿
        Thread loop = Thread.ofPlatform().daemon(true).name("skills-watch").start(() ->
                watchLoop(watcher, watched, roots, disabled, catalogRefresher, stopped));
        return () -> {
            stopped.set(true);
            loop.interrupt();
            try {
                watcher.close();
            } catch (IOException ignored) {
                // 停表路径：watch 服务关闭失败无补救动作
            }
        };
    }

    /**
     * watch 主循环：事件 → 短去抖窗口合并 → 重扫 → digest 判定 → 片段回调；
     * 每轮后补注册新出现的根（根目录创建事件后其内部变更才能被监视）。
     * take 阻塞被打断 / watch 关闭即退出。
     */
    private void watchLoop(java.nio.file.WatchService watcher, Map<Path, WatchKey> watched,
                           List<Path> roots, Set<String> disabled, Runnable catalogRefresher,
                           java.util.concurrent.atomic.AtomicBoolean stopped) {
        while (!stopped.get()) {
            WatchKey key = null;
            try {
                key = watcher.take();
                Thread.sleep(200); // 去抖：编辑器常一次性写多事件，窗口内合并为一次重扫
                WatchKey queued;
                while ((queued = watcher.poll()) != null) {
                    drain(queued, watched); // 排干积压并复位——key 不复位即永久停报（WatchService 契约）
                }
                boolean catalogChanged = refreshFrom(roots, disabled);
                if (catalogChanged) {
                    catalogRefresher.run();
                }
                for (Path root : roots) {
                    registerRootAndParent(watcher, root, watched);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // 停表路径
            } catch (java.nio.file.ClosedWatchServiceException e) {
                return; // 停表路径：watch 已关
            } catch (RuntimeException e) {
                log.warn("技能热加载 watch 异常（{}）——本轮跳过，继续监视", e.toString(), e);
            } finally {
                if (key != null) {
                    drain(key, watched); // 复位覆盖所有路径：异常轮不复位即该根静默失聪
                }
            }
        }
    }

    /**
     * 清空 key 事件并复位；复位失败（目录被删——WatchService 自动 cancel）时
     * 从防重集合摘除，目录重建后才能重新注册（否则该根热加载永久失效）。
     */
    private static void drain(WatchKey key, Map<Path, WatchKey> watched) {
        key.pollEvents();
        if (!key.reset()) {
            watched.remove(key.watchable());
        }
    }

    /** 注册根（存在时）与其父级（捕捉根目录首次创建）；重复注册静默跳过。 */
    private static void registerRootAndParent(java.nio.file.WatchService watcher, Path root,
                                              Map<Path, WatchKey> watched) {
        registerIfAbsent(watcher, root, watched);
        Path parent = root.getParent();
        if (parent != null && Files.isDirectory(parent)) {
            registerIfAbsent(watcher, parent, watched);
        }
    }

    /**
     * 注册目录（已在场且现存 key 仍有效则跳过）。目录删除时 Poller 会直接
     * cancel 其 key（不投递事件、不唤醒 take）——防重表里残留失效 key 会让
     * 重建后的目录永久失聪（M23 工单 09 验收实测），故失效即摘除并允许重注册。
     */
    private static void registerIfAbsent(java.nio.file.WatchService watcher, Path dir,
                                         Map<Path, WatchKey> watched) {
        if (!Files.isDirectory(dir)) {
            return; // 目录不在场
        }
        WatchKey existing = watched.get(dir);
        if (existing != null) {
            if (existing.isValid()) {
                return; // 仍有效：已注册
            }
            watched.remove(dir); // 失效（目录曾被删除自动 cancel）：摘除后重注册
        }
        try {
            WatchKey key = dir.register(watcher,
                    java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                    java.nio.file.StandardWatchEventKinds.ENTRY_DELETE,
                    java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);
            watched.put(dir, key);
        } catch (IOException e) {
            log.warn("技能发现根 watch 注册失败（{}）——该根变更不触发热加载", dir.toString());
        }
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
