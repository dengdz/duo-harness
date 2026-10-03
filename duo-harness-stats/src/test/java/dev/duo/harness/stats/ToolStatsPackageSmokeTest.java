package dev.duo.harness.stats;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.PluginRows;
import dev.duo.harness.core.api.PluginState;
import dev.duo.harness.core.api.RowSnapshot;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.tools.ToolsService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 插件包化冒烟（M35 工单 07，ADR-0037 交货样板）：v1 插件包 = 常规 {@code mvn
 * package} 产物（模块 jar 只含自有类）。两查——①包内容排除宿主供给面且保留本
 * 插件类（交货形态正确性）；②jar 行全链路激活且工具在册（自优先加载器 + 宿主
 * 供给的运行期形态）。产物缺席时跳过（先 {@code mvn -pl duo-harness-stats
 * package}；CI 常规 test 不受影响）。
 */
class ToolStatsPackageSmokeTest {

    @TempDir
    Path tempHome;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ToolStatsPackageSmokeTest —— 插件包化样板：包内容排除宿主供给面、"
                + "jar 行激活与工具在册（2 用例，产物缺席自动跳过） ===");
    }

    private Path packageJar() throws IOException {
        try (Stream<Path> entries = Files.list(Path.of("target"))) {
            return entries.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("duo-harness-stats-") && name.endsWith(".jar");
                    })
                    .findFirst().orElse(null);
        }
    }

    interface ToolsView {
        ToolsService tools();
    }

    @Test
    void packageJarExcludesHostClassesAndKeepsPluginClasses() throws IOException {
        Path jar = packageJar();
        assumeTrue(jar != null, "插件包产物未构建（mvn -pl duo-harness-stats package 后运行）");

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            List<String> entryNames = jarFile.stream().map(e -> e.getName()).toList();
            assertTrue(entryNames.stream().anyMatch(n -> n.startsWith("dev/duo/harness/stats/")),
                    "本插件类应在包内: " + entryNames);
            assertTrue(entryNames.stream().noneMatch(n -> n.startsWith("dev/duo/harness/core/")),
                    "宿主 core 不得打入插件包（自优先加载器约定）: " + entryNames);
            assertTrue(entryNames.stream().noneMatch(n -> n.startsWith("dev/duo/harness/tools/")),
                    "宿主工具域不得打入插件包: " + entryNames);
            assertTrue(entryNames.stream().noneMatch(n -> n.startsWith("com/fasterxml/")),
                    "宿主供给的公共库不得打入插件包: " + entryNames);
        }
    }

    @Test
    void packageJarLoadsViaJarRowAndActivates() throws Exception {
        Path jar = packageJar();
        assumeTrue(jar != null, "插件包产物未构建（mvn -pl duo-harness-stats package 后运行）");

        System.setProperty(DuoHome.PROP_OVERRIDE, tempHome.toString());
        try {
            // 装配树：tools + commands 喂 ToolStatsPlugin 的 inject 依赖，jar 行即样板本体
            Path yml = Files.writeString(tempHome.resolve("plugins.yml"), """
                    plugins:
                      - id: tools
                        name: dev.duo.harness.tools.ToolsPlugin
                      - id: commands
                        name: dev.duo.harness.agent.commands.CommandsPlugin
                        config: {}
                      - id: tool-stats-pkg
                        name: dev.duo.harness.stats.ToolStatsPlugin
                        jar: %s
                    """.formatted(jar.toAbsolutePath()));

            Context root = Boot.from(yml);
            try {
                RowSnapshot row = PluginRows.of(root).rows().stream()
                        .filter(r -> r.id().equals("tool-stats-pkg")).findFirst().orElseThrow();
                assertEquals(PluginState.ACTIVE, row.state(), "插件包行应激活");

                // 功能在册证据：/toolstats 命令背后的 tool_stats 查询工具随包注册
                ToolsService tools = root.as(ToolsView.class).tools();
                assertTrue(tools.list().stream().anyMatch(t -> "tool_stats".equals(t.name())),
                        "tool_stats 工具应随插件包装载注册");
            } finally {
                root.dispose();
            }
        } finally {
            System.clearProperty(DuoHome.PROP_OVERRIDE);
        }
    }
}
