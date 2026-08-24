package dev.duo.harness.core.api;

import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.core.api.boot.BootException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝 A（boot 全链路）用例：yml → 插件树，含审计点名与整体回滚。
 * 测试插件用静态嵌套类（FQCN 可被 Class.forName 寻址）。
 */
class BootTest {

    @TempDir
    Path tempDir;

    // === 测试插件集 ===

    /** 提供问候服务的插件。 */
    public static class GreeterPlugin implements Plugin<Void> {
        static final AtomicInteger DISPOSALS = new AtomicInteger();

        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            ctx.provide("greeter", "你好");
            return DISPOSALS::incrementAndGet;
        }
    }

    /** 依赖 greeter 的消费插件。 */
    public static class ConsumerPlugin implements Plugin<Void> {
        static final AtomicInteger ACTIVATIONS = new AtomicInteger();

        @Override
        public Set<String> inject() {
            return Set.of("greeter");
        }

        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            ACTIVATIONS.incrementAndGet();
            return () -> {
            };
        }
    }

    interface GreeterView {
        String greeter();
    }

    /** 带 config record 的插件。 */
    public record EchoConfig(String prefix, int times) {
    }

    public static class EchoPlugin implements Plugin<EchoConfig> {
        static EchoConfig lastConfig;

        @Override
        public Class<EchoConfig> configType() {
            return EchoConfig.class;
        }

        @Override
        public Disposable apply(Context ctx, EchoConfig config) {
            lastConfig = config;
            return () -> {
            };
        }
    }

    /** 启动即失败的插件。 */
    public static class BrokenPlugin implements Plugin<Void> {
        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            throw new IllegalStateException("装配炸了");
        }
    }

    // === 工具 ===

    private Path writeYaml(String content) throws IOException {
        Path file = tempDir.resolve("plugins.yml");
        Files.writeString(file, content);
        return file;
    }

    // === 接缝 A 用例 ===

    @Test
    void bootActivatesRowsAndServicesAreResolvable() throws Exception {
        Path file = writeYaml("""
                plugins:
                  - id: greeter
                    name: dev.duo.harness.core.api.BootTest$GreeterPlugin
                  - id: consumer
                    name: dev.duo.harness.core.api.BootTest$ConsumerPlugin
                """);
        ConsumerPlugin.ACTIVATIONS.set(0);

        Context root = Boot.from(file);

        assertEquals("你好", root.as(GreeterView.class).greeter());
        root.dispose();
    }

    @Test
    void rowOrderCarriesNoLoadSemantics() throws Exception {
        // 依赖方行在前、提供方行在后：行序不决定加载顺序（04 的依赖驱动保证）
        Path file = writeYaml("""
                plugins:
                  - id: consumer
                    name: dev.duo.harness.core.api.BootTest$ConsumerPlugin
                  - id: greeter
                    name: dev.duo.harness.core.api.BootTest$GreeterPlugin
                """);
        ConsumerPlugin.ACTIVATIONS.set(0);

        Context root = Boot.from(file);

        assertEquals(1, ConsumerPlugin.ACTIVATIONS.get());
        root.dispose();
    }

    @Test
    void disabledRowIsSkippedButKeptInConfig() throws Exception {
        Path file = writeYaml("""
                plugins:
                  - id: greeter
                    name: dev.duo.harness.core.api.BootTest$GreeterPlugin
                    disabled: true
                """);

        Context root = assertDoesNotThrow(() -> Boot.from(file));

        assertFalse(root.hasService("greeter"));
        root.dispose();
    }

    @Test
    void configRecordBindsFromYaml() throws Exception {
        Path file = writeYaml("""
                plugins:
                  - id: echo
                    name: dev.duo.harness.core.api.BootTest$EchoPlugin
                    config:
                      prefix: "回声"
                      times: 3
                """);

        Context root = Boot.from(file);

        assertEquals(new EchoConfig("回声", 3), EchoPlugin.lastConfig);
        root.dispose();
    }

    @Test
    void missingDependencyIsNamedWithServiceList() throws Exception {
        Path file = writeYaml("""
                plugins:
                  - id: consumer
                    name: dev.duo.harness.core.api.BootTest$ConsumerPlugin
                """);
        ConsumerPlugin.ACTIVATIONS.set(0);

        BootException e = assertThrows(BootException.class, () -> Boot.from(file));

        assertEquals(BootException.Stage.ACTIVATE, e.stage());
        assertTrue(e.getMessage().contains("consumer"), e.getMessage());
        assertTrue(e.getMessage().contains("greeter"), "缺失服务应点名: " + e.getMessage());
        assertEquals(0, ConsumerPlugin.ACTIVATIONS.get());
    }

    @Test
    void failedPluginIsNamedWithOriginalCause() throws Exception {
        Path file = writeYaml("""
                plugins:
                  - id: broken
                    name: dev.duo.harness.core.api.BootTest$BrokenPlugin
                """);

        BootException e = assertThrows(BootException.class, () -> Boot.from(file));

        assertTrue(e.getMessage().contains("broken"), e.getMessage());
        assertTrue(e.getMessage().contains("装配炸了"), "原始错误应呈现: " + e.getMessage());
    }

    @Test
    void badConfigFieldIsLocatedToRow() throws Exception {
        Path file = writeYaml("""
                plugins:
                  - id: echo
                    name: dev.duo.harness.core.api.BootTest$EchoPlugin
                    config:
                      prefix: "缺 times 字段"
                """);

        BootException e = assertThrows(BootException.class, () -> Boot.from(file));

        assertTrue(e.getMessage().contains("echo"), e.getMessage());
        assertTrue(e.getMessage().contains("times"), "缺字段应定位: " + e.getMessage());
    }

    @Test
    void unknownClassIsNamed() throws Exception {
        Path file = writeYaml("""
                plugins:
                  - id: ghost
                    name: com.no.such.Class
                """);

        BootException e = assertThrows(BootException.class, () -> Boot.from(file));

        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
        assertTrue(e.getMessage().contains("com.no.such.Class"), e.getMessage());
    }

    @Test
    void anyFailureRollsBackTheWholeTree() throws Exception {
        Path file = writeYaml("""
                plugins:
                  - id: greeter
                    name: dev.duo.harness.core.api.BootTest$GreeterPlugin
                  - id: broken
                    name: dev.duo.harness.core.api.BootTest$BrokenPlugin
                """);
        GreeterPlugin.DISPOSALS.set(0);

        assertThrows(BootException.class, () -> Boot.from(file));

        // 整树回滚的直接证据：失败前已激活的 greeter 其 disposer 已执行
        assertEquals(1, GreeterPlugin.DISPOSALS.get());
    }

    @Test
    void malformedYamlFailsAtParseStage() throws Exception {
        Path file = writeYaml("plugins: [ 不是合法结构");

        BootException e = assertThrows(BootException.class, () -> Boot.from(file));

        assertEquals(BootException.Stage.PARSE_CONFIG, e.stage());
    }

    @Test
    void missingFileFailsAtReadStage() {
        BootException e = assertThrows(BootException.class,
                () -> Boot.from(tempDir.resolve("不存在.yml")));

        assertEquals(BootException.Stage.READ_CONFIG, e.stage());
    }

    @Test
    void missingIdFailsAtParseStage() throws Exception {
        Path file = writeYaml("""
                plugins:
                  - name: dev.duo.harness.core.api.BootTest$GreeterPlugin
                """);

        BootException e = assertThrows(BootException.class, () -> Boot.from(file));

        assertEquals(BootException.Stage.PARSE_CONFIG, e.stage());
        assertTrue(e.getMessage().contains("id"), e.getMessage());
    }

    @Test
    void strayDashEntryFailsAtParseStageNotNpe() throws Exception {
        Path file = writeYaml("""
                plugins:
                  -
                  - id: greeter
                    name: dev.duo.harness.core.api.BootTest$GreeterPlugin
                """);

        BootException e = assertThrows(BootException.class, () -> Boot.from(file));

        assertEquals(BootException.Stage.PARSE_CONFIG, e.stage());
        assertTrue(e.getMessage().contains("空行"), e.getMessage());
    }

    @Test
    void duplicateRowIdFailsAtParseStage() throws Exception {
        Path file = writeYaml("""
                plugins:
                  - id: greeter
                    name: dev.duo.harness.core.api.BootTest$GreeterPlugin
                  - id: greeter
                    name: dev.duo.harness.core.api.BootTest$EchoPlugin
                    config:
                      prefix: "重复"
                      times: 1
                """);

        BootException e = assertThrows(BootException.class, () -> Boot.from(file));

        assertEquals(BootException.Stage.PARSE_CONFIG, e.stage());
        assertTrue(e.getMessage().contains("重复"), e.getMessage());
    }
}
