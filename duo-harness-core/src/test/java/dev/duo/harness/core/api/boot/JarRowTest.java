package dev.duo.harness.core.api.boot;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.PluginRows;
import dev.duo.harness.core.api.PluginState;
import dev.duo.harness.core.api.RowSnapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 S1（boot 全链路）用例：插件包（jar: 行）装载（ADR-0037 决策二）——
 * jar 行装载与服务在册、同 FQCN 跨包类隔离（自优先加载器的决定性证据）、
 * 坏包点名（缺文件/损坏包）、解析校验（空白 jar 字段）、拔除释放加载器后可重装。
 * fixture jar 在测试内现打：夹具类字节 + 每 包不同的 marker 资源。
 */
class JarRowTest {

    private static final String FIXTURE_FQCN = "dev.duo.harness.core.api.boot.JarFixturePlugin";

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：JarRowTest —— 插件包装载：jar 行装载、跨包类隔离、"
                + "坏包点名、解析校验、拔除释放（6 用例） ===");
    }

    @TempDir
    Path tempDir;

    // === 夹具 ===

    /** 现打 fixture jar：夹具类字节 + 指定 marker 资源（文件名即 marker 文件名，内容 = marker 值）。 */
    private Path buildJar(String fileName, String markerFile, String markerValue) throws IOException {
        Path jar = tempDir.resolve(fileName);
        byte[] classBytes;
        try (InputStream in = JarFixturePlugin.class.getResourceAsStream("JarFixturePlugin.class")) {
            classBytes = in.readAllBytes();
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("dev/duo/harness/core/api/boot/JarFixturePlugin.class"));
            out.write(classBytes);
            out.closeEntry();
            out.putNextEntry(new JarEntry(markerFile));
            out.write(markerValue.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private Path writeYaml(String content) throws IOException {
        Path file = tempDir.resolve("plugins-" + System.nanoTime() + ".yml");
        Files.writeString(file, content);
        return file;
    }

    public interface JarOneView {
        String jarOneMarker();
    }

    public interface JarTwoView {
        String jarTwoMarker();
    }

    // === 用例 ===

    @Test
    void jarRowLoadsAndServesPackageResource() throws Exception {
        Path jar = buildJar("box-one.jar", "marker-one.txt", "jar-one");
        Path yml = writeYaml("""
                plugins:
                  - id: box
                    name: %s
                    jar: %s
                    config:
                      serviceName: jarOneMarker
                      markerFile: marker-one.txt
                """.formatted(FIXTURE_FQCN, jar));

        Context root = Boot.from(yml);

        // 服务值来自 jar 内资源（非宿主 classpath 副本——宿主副本读不到 marker）
        assertEquals("jar-one", root.as(JarOneView.class).jarOneMarker());
        RowSnapshot row = PluginRows.of(root).rows().get(0);
        assertEquals("box", row.id());
        assertEquals(FIXTURE_FQCN, row.pluginName());
        assertEquals(PluginState.ACTIVE, row.state());
        root.dispose();
    }

    @Test
    void sameFqcnInTwoJarsIsIsolatedPerLoader() throws Exception {
        Path jarOne = buildJar("iso-one.jar", "marker-one.txt", "jar-one");
        Path jarTwo = buildJar("iso-two.jar", "marker-two.txt", "jar-two");
        Path yml = writeYaml("""
                plugins:
                  - id: box1
                    name: %s
                    jar: %s
                    config:
                      serviceName: jarOneMarker
                      markerFile: marker-one.txt
                  - id: box2
                    name: %s
                    jar: %s
                    config:
                      serviceName: jarTwoMarker
                      markerFile: marker-two.txt
                """.formatted(FIXTURE_FQCN, jarOne, FIXTURE_FQCN, jarTwo));

        Context root = Boot.from(yml);

        // 同 FQCN 两包各自读本包 marker：自优先加载器的决定性证据
        // （parent-first 会统一落到宿主 classpath 副本，第二个包读不到 marker）
        assertEquals("jar-one", root.as(JarOneView.class).jarOneMarker());
        assertEquals("jar-two", root.as(JarTwoView.class).jarTwoMarker());
        root.dispose();
    }

    @Test
    void missingJarFileIsNamedAtActivate() throws Exception {
        Path missing = tempDir.resolve("不存在.jar");
        Path yml = writeYaml("""
                plugins:
                  - id: box
                    name: %s
                    jar: %s
                """.formatted(FIXTURE_FQCN, missing));

        BootException e = assertThrows(BootException.class, () -> Boot.from(yml));

        assertTrue(e.getMessage().contains("box"), e.getMessage());
        assertTrue(e.getMessage().contains("不存在"), "缺包应点名路径: " + e.getMessage());
    }

    @Test
    void corruptJarIsNamedAtActivate() throws Exception {
        Path corrupt = tempDir.resolve("corrupt.jar");
        Files.writeString(corrupt, "这不是一个 zip，也不是一个 jar");
        Path yml = writeYaml("""
                plugins:
                  - id: box
                    name: %s
                    jar: %s
                """.formatted(FIXTURE_FQCN, corrupt));

        BootException e = assertThrows(BootException.class, () -> Boot.from(yml));

        assertTrue(e.getMessage().contains("box"), e.getMessage());
    }

    @Test
    void blankJarFieldFailsAtParseStage() throws Exception {
        Path yml = writeYaml("""
                plugins:
                  - id: box
                    name: %s
                    jar: ""
                """.formatted(FIXTURE_FQCN));

        BootException e = assertThrows(BootException.class, () -> Boot.from(yml));

        assertEquals(BootException.Stage.PARSE_CONFIG, e.stage());
        assertTrue(e.getMessage().contains("jar 字段"), e.getMessage());
    }

    @Test
    void disposeByIdReleasesLoaderAndAllowsReload() throws Exception {
        Path jar = buildJar("box-reload.jar", "marker-one.txt", "jar-one");
        Path yml = writeYaml("""
                plugins:
                  - id: box
                    name: %s
                    jar: %s
                    config:
                      serviceName: jarOneMarker
                      markerFile: marker-one.txt
                """.formatted(FIXTURE_FQCN, jar));

        Context root = Boot.from(yml);
        PluginRows control = PluginRows.of(root);
        control.dispose("box");
        assertFalse(root.hasService("jarOneMarker"));

        // 拔除后同 id 重装（closer 通道不阻碍复用；重装件走宿主 classpath 副本）
        PluginHandle reloaded = assertDoesNotThrow(() -> control.load("box",
                new JarFixturePlugin(), Map.of("serviceName", "jarOneMarker",
                        "markerFile", "marker-one.txt")));
        assertEquals(PluginState.ACTIVE, reloaded.state());
        assertTrue(root.hasService("jarOneMarker"));
        root.dispose();
    }
}
