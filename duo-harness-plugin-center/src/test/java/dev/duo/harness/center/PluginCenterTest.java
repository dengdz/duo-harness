package dev.duo.harness.center;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.PluginState;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.core.api.boot.DuoHome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 S2（插件装配缝）用例：插件中心全流程（M35 工单 05）——树内装配（插件
 * 上下文声明闸门在场的真实纪律，M34 三盲教训）下的扫描/点名/装/停/启/卸/
 * 不可拔点名/状态合并视图。DUO_HOME 注入临时目录；fixture jar 测试内现打。
 */
class PluginCenterTest {

    private static final String FIXTURE_FQCN = "dev.duo.harness.center.CenterFixturePlugin";
    private static final String CENTER_FQCN = "dev.duo.harness.center.PluginCenterPlugin";

    @TempDir
    Path tempHome;

    private Context root;
    private PluginCenter center;
    private Path fixtureJar;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：PluginCenterTest —— 插件中心全流程：扫描/点名/装/停/启/卸/"
                + "不可拔点名/状态合并（6 用例，S2 装配缝） ===");
    }

    interface PluginCenterView {
        PluginCenter pluginCenter();
    }

    interface BoxView {
        String boxMarker();
    }

    @BeforeEach
    void 注入Home并装配树() throws Exception {
        System.setProperty(DuoHome.PROP_OVERRIDE, tempHome.toString());
        Files.writeString(tempHome.resolve("plugins.yml"), """
                plugins:
                  - id: plugin-center
                    name: %s
                """.formatted(CENTER_FQCN));
        root = Boot.from(tempHome.resolve("plugins.yml"));
        center = root.as(PluginCenterView.class).pluginCenter();
        fixtureJar = buildJar("fixture.jar", "marker.txt", "来自插件包");
    }

    @AfterEach
    void 收树清属性() {
        if (root != null) {
            root.dispose();
        }
        System.clearProperty(DuoHome.PROP_OVERRIDE);
    }

    // === 夹具 ===

    private Path buildJar(String fileName, String markerFile, String markerValue) throws IOException {
        Path pluginsDir = tempHome.resolve("plugins");
        Files.createDirectories(pluginsDir);
        Path jar = pluginsDir.resolve(fileName);
        byte[] classBytes;
        try (InputStream in = CenterFixturePlugin.class.getResourceAsStream("CenterFixturePlugin.class")) {
            classBytes = in.readAllBytes();
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("dev/duo/harness/center/CenterFixturePlugin.class"));
            out.write(classBytes);
            out.closeEntry();
            out.putNextEntry(new JarEntry(markerFile));
            out.write(markerValue.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private String ymlText() throws IOException {
        return Files.readString(tempHome.resolve("plugins.yml"));
    }

    // === 用例 ===

    @Test
    void inspectDiscoversCandidateAndPackageTriple() {
        PluginCenter.InstallInspection inspection = center.inspect(fixtureJar);

        assertEquals(fixtureJar, inspection.path());
        assertTrue(inspection.sizeBytes() > 0);
        assertEquals(64, inspection.sha256().length());
        assertTrue(inspection.candidateEntries().contains(FIXTURE_FQCN),
                "字节启发式应发现夹具候选入口: " + inspection.candidateEntries());
    }

    @Test
    void installServesPersistsAndScanStopsListing() throws Exception {
        List<PluginCenter.ScannedPackage> before = center.scan();
        assertEquals(1, before.size(), "已放入目录的 fixture 应被扫出");

        PluginCenter.InstallInspection inspection = center.inspect(fixtureJar);
        center.install(fixtureJar, "box", inspection.candidateEntries().get(0),
                Map.of("serviceName", "boxMarker", "markerFile", "marker.txt"));

        // 运行期生效：服务值来自包内 marker，行在册 ACTIVE
        assertEquals("来自插件包", root.as(BoxView.class).boxMarker());
        assertEquals("box", center.status().stream()
                .filter(r -> r.id().equals("box")).findFirst().orElseThrow().id());
        assertEquals(PluginState.ACTIVE.name(), center.status().stream()
                .filter(r -> r.id().equals("box")).findFirst().orElseThrow().state());

        // 装配文件落 jar 行（单一事实源）
        assertTrue(ymlText().contains("box"), "装配文件应含新行 id");
        assertTrue(ymlText().contains(fixtureJar.normalize().toString()), "装配文件应含 jar 来源");

        // 已装包不再进待装清单
        assertTrue(center.scan().isEmpty(), "已安装包不得重复出现在待装清单");
    }

    @Test
    void disableEnableCyclePersistsDisabledFlag() throws Exception {
        center.install(fixtureJar, "box", FIXTURE_FQCN,
                Map.of("serviceName", "boxMarker", "markerFile", "marker.txt"));

        center.disable("box");

        assertFalse(root.hasService("boxMarker"), "停用即拔除");
        assertTrue(center.status().stream().filter(r -> r.id().equals("box"))
                .findFirst().orElseThrow().disabled(), "停用行 status 应标 disabled");
        assertTrue(ymlText().contains("disabled: true"), "停用应落盘");

        center.enable("box");

        assertEquals("来自插件包", root.as(BoxView.class).boxMarker(), "启用即重建装载");
        assertFalse(ymlText().contains("disabled: true"), "启用应翻回");
    }

    @Test
    void uninstallRemovesRuntimeAndRowAndScanResumes() throws Exception {
        center.install(fixtureJar, "box", FIXTURE_FQCN,
                Map.of("serviceName", "boxMarker", "markerFile", "marker.txt"));

        center.uninstall("box");

        assertFalse(root.hasService("boxMarker"));
        assertFalse(ymlText().contains("jar:"), "卸载应删行");
        assertTrue(center.status().stream().noneMatch(r -> r.id().equals("box")));
        assertEquals(1, center.scan().size(), "卸载后同包可再被发现");
    }

    @Test
    void disableCenterSelfIsNamedAsRestartOnly() {
        // 不可拔清单：插件中心自身（cli 呈现位同清单，类不在本模块依赖——FQCN 字面）
        PluginException e = assertThrows(PluginException.class, () -> center.disable("plugin-center"));

        assertTrue(e.getMessage().contains("plugin-center"), e.getMessage());
        assertTrue(e.getMessage().contains("需重启生效"), e.getMessage());
    }

    @Test
    void unknownIdOperationsAreNamed() {
        assertThrows(PluginException.class, () -> center.disable("no-such"));
        assertThrows(PluginException.class, () -> center.enable("no-such"));
        assertThrows(PluginException.class, () -> center.uninstall("no-such"));
        assertThrows(PluginException.class, () -> center.inspect(tempHome.resolve("不存在.jar")));
    }

    @Test
    void installClasspathEmptyConfigServesJsonNodeConfigTypePlugin() {
        // 回归锁（M35 工单 06 实测）：JsonNode config 型插件给空 {} 是合法配置——
        // 归一化按 configType 分流，不得把空对象误判为"未提供配置"
        center.installClasspath("json-node-row", JsonNodeConfigPlugin.class.getName(), Map.of());

        assertTrue(center.status().stream()
                .filter(r -> r.id().equals("json-node-row"))
                .findFirst().orElseThrow().state().equals("ACTIVE"));
        center.uninstall("json-node-row");
    }
}
