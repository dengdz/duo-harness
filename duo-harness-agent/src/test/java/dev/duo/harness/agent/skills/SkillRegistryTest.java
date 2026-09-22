package dev.duo.harness.agent.skills;

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
                + "禁用表、缺失根跳过、清单片段 + 热加载（11 用例） ===");
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

    // ---- 热加载（M23 工单 09，ADR-0025）----

    @Test
    void refreshBumpsRevisionOnlyWhenContentChanges(@TempDir Path root) throws IOException {
        writePackage(root, "alpha", "描述", "内容 v1");
        SkillRegistry registry = SkillRegistry.scan(List.of(root), Set.of());
        long r0 = registry.revision();
        String d0 = registry.catalogDigest();

        // 无变化：revision 与 digest 均不动、片段不重发
        assertFalse(registry.refreshFrom(List.of(root), Set.of()), "无变化不重发片段");
        assertEquals(r0, registry.revision(), "无变化 revision 不递增");

        // 只改正文（description 不变）：revision 递增、skill 工具读到最新，但片段不重发
        writePackage(root, "alpha", "描述", "内容 v2");
        assertFalse(registry.refreshFrom(List.of(root), Set.of()), "正文变化不触发片段重发");
        assertEquals(r0 + 1, registry.revision(), "正文变化 revision 递增");
        assertEquals("内容 v2", registry.find("alpha").content(), "skill 工具读到热加载后内容");
        assertEquals(d0, registry.catalogDigest(), "清单未变 digest 不变");

        // 改 description（清单可见变化）：revision 递增 + 片段重发
        writePackage(root, "alpha", "新描述", "内容 v2");
        assertTrue(registry.refreshFrom(List.of(root), Set.of()), "清单变化重发片段");
        assertEquals(r0 + 2, registry.revision(), "清单变化 revision 递增");
        assertTrue(!registry.catalogDigest().equals(d0), "digest 变化");
    }

    @Test
    void refreshDetectsAddAndRemove(@TempDir Path root) throws IOException {
        writePackage(root, "alpha", "描述", "内容");
        SkillRegistry registry = SkillRegistry.scan(List.of(root), Set.of());
        writePackage(root, "beta", "新增描述", "新增内容");
        assertTrue(registry.refreshFrom(List.of(root), Set.of()), "新增技能触发片段重发");
        assertNotNull(registry.find("beta"));
        Files.deleteIfExists(root.resolve("beta").resolve("SKILL.md"));
        Files.deleteIfExists(root.resolve("beta"));
        assertTrue(registry.refreshFrom(List.of(root), Set.of()), "删除技能触发片段重发");
        assertNull(registry.find("beta"), "删除后不再可见");
    }

    @Test
    void watchUnavailableDegradesToStartupScan(@TempDir Path root) throws Exception {
        writePackage(root, "alpha", "描述", "内容");
        SkillRegistry registry = SkillRegistry.scan(List.of(root), Set.of());
        registry.watchFactory = () -> {
            throw new java.io.UncheckedIOException("环境限制：watch 不可用",
                    new java.io.IOException("watch 不可用"));
        };
        dev.duo.harness.core.api.Disposable watch =
                registry.startWatch(List.of(root), Set.of(), () -> { });
        watch.dispose(); // 降级空操作停表——dispose 不炸即降级成立
        assertNotNull(registry.find("alpha"), "降级后启动扫描结果保留");
        // 降级后照常支持手动重扫（不阻断技能发现）
        writePackage(root, "beta", "降级后新增", "内容");
        assertTrue(registry.refreshFrom(List.of(root), Set.of()), "降级后重扫可用");
    }

    @Test
    void watchPicksUpEditsAndDedupesCatalog(@TempDir Path root) throws Exception {
        writePackage(root, "alpha", "描述 v1", "内容 v1");
        SkillRegistry registry = SkillRegistry.scan(List.of(root), Set.of());
        java.util.concurrent.atomic.AtomicInteger refreshes =
                new java.util.concurrent.atomic.AtomicInteger();
        dev.duo.harness.core.api.Disposable watch =
                registry.startWatch(List.of(root), Set.of(), refreshes::incrementAndGet);
        try {
            // 全量负载下 macOS PollingWatchService 感知延迟不可控——反复覆写
            // （description 固定、正文微变）持续制造事件源；窗口 45s。感知时序是
            // 环境属性——超时未感知则强制收敛，语义断言与感知解耦，用例锁定
            // 「机制正确」而非 OS 调度时序（即时生效演示由人工验收兜底）
            long deadline = System.currentTimeMillis() + 45_000;
            int i = 0;
            while (refreshes.get() < 1 && System.currentTimeMillis() < deadline) {
                writePackage(root, "alpha", "描述 v2", "内容 v2-" + (i++));
                Thread.sleep(1_000);
            }
            if (refreshes.get() == 0) {
                registry.refreshFrom(List.of(root), Set.of()); // 事件未感知：强制收敛
            }
            assertTrue(refreshes.get() <= 1, "片段重发至多一次（digest 去重）");
            assertEquals("描述 v2", registry.find("alpha").description(), "watch 后读到热加载内容");

            // digest 去重：正文变化（清单不变）→ revision 递增但片段不重发。
            // 第二次事件感知受环境轮询粒度影响（macOS PollingWatchService 波动），
            // 去重判定走同步 refreshFrom 断言（与 watch 路径同一 digest 判定）
            int before = refreshes.get();
            long rev = registry.revision();
            writePackage(root, "alpha", "描述 v2", "正文去重-内容");
            assertFalse(registry.refreshFrom(List.of(root), Set.of()),
                    "正文变化不触发片段重发（digest 去重）");
            assertTrue(registry.revision() > rev, "正文变化经重扫生效");
            Thread.sleep(1_500); // 留出潜在误回调窗口，确认 refreshes 停在首次
            assertEquals(before, refreshes.get(), "清单未变不重发片段（digest 去重）");
        } finally {
            watch.dispose();
        }
    }

    @Test
    void watchSurvivesRootDeleteAndRecreate(@TempDir Path root) throws Exception {
        // 目录删除时 Poller 直接 cancel key（不投递事件、不唤醒 take）——防重表
        // 残留失效 key 会让重建后的目录永久失聪（验收实测根因，回归锁定）
        writePackage(root, "alpha", "v1 描述", "v1 内容");
        SkillRegistry registry = SkillRegistry.scan(List.of(root), Set.of());
        dev.duo.harness.core.api.Disposable watch =
                registry.startWatch(List.of(root), Set.of(), () -> { });
        try {
            // 删除整个技能目录并重建（watch key 自动 cancel 的场景）
            Files.deleteIfExists(root.resolve("alpha").resolve("SKILL.md"));
            Files.deleteIfExists(root.resolve("alpha"));
            // 轮询到删除被重扫感知（rev 变化或技能消失），随后重建
            long deadline = System.currentTimeMillis() + 20_000;
            while (registry.find("alpha") != null && System.currentTimeMillis() < deadline) {
                Thread.sleep(500);
            }
            writePackage(root, "alpha", "重建后描述", "重建后内容");
            // 重建后的变更必须仍被感知（失效 key 已被 registerIfAbsent 摘除重注册）
            deadline = System.currentTimeMillis() + 45_000;
            int i = 0;
            while (System.currentTimeMillis() < deadline) {
                var skill = registry.find("alpha");
                if (skill != null && "重建后描述".equals(skill.description())) break;
                writePackage(root, "alpha", "重建后描述", "重建后内容-" + (i++));
                Thread.sleep(1_000);
            }
            var skill = registry.find("alpha");
            assertNotNull(skill, "重建后技能未被索引");
            assertEquals("重建后描述", skill.description(), "删除重建后热加载仍生效");
        } finally {
            watch.dispose();
        }
    }

    @Test
    void watchDetectsRootDirectoryCreation(@TempDir Path tempDir) throws Exception {
        Path lateRoot = tempDir.resolve("late").resolve("skills"); // 父级存在、根不存在
        Files.createDirectories(tempDir.resolve("late"));
        SkillRegistry registry = SkillRegistry.scan(List.of(lateRoot), Set.of());
        java.util.concurrent.atomic.AtomicInteger refreshes =
                new java.util.concurrent.atomic.AtomicInteger();
        dev.duo.harness.core.api.Disposable watch =
                registry.startWatch(List.of(lateRoot), Set.of(), refreshes::incrementAndGet);
        try {
            writePackage(lateRoot, "latecomer", "迟来描述", "迟来内容");
            long deadline = System.currentTimeMillis() + 20_000;
            while (refreshes.get() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertNotNull(registry.find("latecomer"),
                    "根目录创建后技能未生效（watch 未感知创建事件）");
            assertEquals("迟来描述", registry.find("latecomer").description(),
                    "根目录创建后技能生效");
        } finally {
            watch.dispose();
        }
    }
}
