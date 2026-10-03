package dev.duo.harness.center;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.PluginRows;
import dev.duo.harness.core.api.RowSnapshot;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.core.internal.boot.BootLoader;
import dev.duo.harness.core.internal.boot.PluginJarClassLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 插件中心服务（M35 工单 05）：目录扫描 → 装前点名 → 装/停/卸/启用编排 →
 * 装配文件写回——一切操作以 {@code DUO_HOME/plugins.yml} 为唯一事实源（结构化
 * 重写，注释不保留是既定口径），运行期挂拔经 {@code pluginRows} 行级控制口。
 *
 * <p>装前点名零副作用：不装载类，只做 jar 条目级扫描——候选入口类按字节串启发
 * （类文件常量池引用 core 的 Plugin 接口名即候选），候选与最终装载正确性解耦
 * （装载失败由行级控制口点名）。</p>
 *
 * <p>不可拔清单（cli 呈现位 apply 即 REPL 主循环、插件中心自身）：停用/启用
 * 一律点名"需重启生效"——防"页面一点把终端主循环拔了/挂死装载线程"。</p>
 */
public final class PluginCenter {

    /** 服务名（消费方视图接口方法名与此逐字一致）。 */
    public static final String SERVICE_NAME = "pluginCenter";

    /** 插件包目录名（DUO_HOME 下；spec 约定 ~/.duo/plugins/）。 */
    public static final String PLUGINS_DIR = "plugins";

    /** 不可拔清单：停用/启用都点名"需重启生效"。cli 的 apply 即 REPL 主循环
     * （M27 实测）；cli 类不进本模块依赖——FQCN 字面是刻意为之。 */
    private static final Set<String> UNDISABLEABLE = Set.of(
            "dev.duo.harness.cli.CliPlugin",
            PluginCenterPlugin.class.getName());

    /** core Plugin 接口的字节串（候选入口启发式：类文件常量池引用即候选）。 */
    private static final String PLUGIN_INTERFACE_MARKER =
            "dev/duo/harness/core/api/Plugin";

    private final PluginRows rows;
    /** 装配文件读写：读宽容（可选字段缺席为 null、未知字段跳过——与 boot 行解析同口径），写省略 null 字段。 */
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    PluginCenter(PluginRows rows) {
        this.rows = rows;
    }

    // === 消费视图（视图接口方法名 = 服务名，逐字一致；放本类内便于同库测试） ===

    /** pluginRows 服务的消费视图（本插件 inject 声明内读取）。 */
    public interface PluginRowsView {
        PluginRows pluginRows();
    }

    // === 结果记录 ===

    /** 待装插件包（扫描产出；sha256 供点名校展示，非信任锚）。 */
    public record ScannedPackage(Path path, long sizeBytes, String sha256) {
    }

    /** 装前点名校：包三元组 + 候选入口类（字节启发式，供页面预填与确认）。 */
    public record InstallInspection(Path path, long sizeBytes, String sha256,
                                    List<String> candidateEntries) {
    }

    /** 行状态（运行期与装配文件合并视图；disabled 行不在运行期，state 为 "-"）。
     * disableable=false 的行页面出"需重启生效"标注；configJson 供"停→改→启"预填。 */
    public record CenterRow(String id, String pluginName, String state,
                            boolean disabled, String jarPath,
                            boolean disableable, String configJson) {
    }

    // === 扫描 ===

    /** 插件目录待装清单：*.jar 且未被装配文件引用（按规范化绝对路径对账）。 */
    public List<ScannedPackage> scan() {
        TreeSet<String> installed = new TreeSet<>();
        for (RowModel row : readRows()) {
            if (row.jar() != null && !row.jar().isBlank()) {
                installed.add(normalize(row.jar()));
            }
        }
        List<ScannedPackage> result = new ArrayList<>();
        Path dir = pluginDir();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted()
                    .forEach(p -> {
                        String abs = normalize(p.toString());
                        if (installed.contains(abs)) {
                            return;
                        }
                        try {
                            result.add(new ScannedPackage(p, Files.size(p), sha256(Files.readAllBytes(p))));
                        } catch (IOException e) {
                            throw new PluginException("插件包读取失败: " + p + "（" + e.getMessage() + "）", e);
                        }
                    });
        } catch (IOException e) {
            throw new PluginException("插件目录扫描失败: " + dir + "（" + e.getMessage() + "）", e);
        }
        return result;
    }

    // === 装前点名 ===

    /**
     * 装前点名：包三元组 + 候选入口类清单（零副作用——不装载任何类）。候选按
     * 字节串启发，可能多报（引用即候选）也可能漏（极端混淆）——最终以装载为准。
     */
    public InstallInspection inspect(Path jar) {
        if (!Files.isRegularFile(jar)) {
            throw new PluginException("插件包文件不存在: " + jar);
        }
        List<String> candidates = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            jarFile.stream().filter(entry -> entry.getName().endsWith(".class")).forEach(entry -> {
                try (InputStream in = jarFile.getInputStream(entry)) {
                    if (referencesPluginInterface(in.readAllBytes())) {
                        candidates.add(classNameOf(entry.getName()));
                    }
                } catch (IOException e) {
                    throw new PluginException("插件包条目读取失败: " + entry.getName(), e);
                }
            });
        } catch (IOException e) {
            throw new PluginException("插件包不是有效的 jar: " + jar + "（" + e.getMessage() + "）", e);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(jar);
        } catch (IOException e) {
            throw new PluginException("插件包读取失败: " + jar, e);
        }
        return new InstallInspection(jar, bytes.length, sha256(bytes), candidates);
    }

    // === 装 / 停 / 卸 / 启用 ===

    /**
     * 安装：装载（行级控制口挂根作用域，类加载器随行）→ 装配文件追加 jar 行。
     * 写回失败即回滚运行期装载再抛错——运行态与装配文件不分裂。
     */
    public void install(Path jar, String id, String entryFqcn, Map<String, Object> config) {
        Objects.requireNonNull(jar, "jar");
        requireId(id);
        Objects.requireNonNull(entryFqcn, "entryFqcn");
        // 空 map 归一为 null：configType=null 的插件"声明即须不提供"（页面空 JSON = 无配置）
        final Map<String, Object> normalized = config == null || config.isEmpty() ? null : config;
        PluginJarClassLoader loader = PluginJarClassLoader.open(jar);
        try {
            Plugin<?> plugin = (Plugin<?>) loader.loadPluginClass(entryFqcn)
                    .getDeclaredConstructor().newInstance();
            try {
                rows.load(id, plugin, normalized, loader);
            } catch (RuntimeException e) {
                closeQuietly(loader);
                throw e;
            }
        } catch (PluginException e) {
            closeQuietly(loader);
            throw e;
        } catch (Exception | LinkageError e) {
            closeQuietly(loader);
            throw new PluginException("插件装载失败: " + entryFqcn + "（jar: " + jar
                    + "；原始错误: " + e + "）", e);
        }
        try {
            mutateYml(rowsNow -> {
                rowsNow.add(new RowModel(id, entryFqcn, normalize(jar.toString()),
                        normalized == null ? null : yaml.valueToTree(normalized), false));
                return rowsNow;
            });
        } catch (RuntimeException e) {
            rows.dispose(id); // 写回失败即回滚运行期装载——两本账不分裂
            throw e;
        }
    }

    /** 停用：运行期拔除 + 装配行置 {@code disabled: true}（重启后仍停用）。 */
    public void disable(String id) {
        RowModel row = requireYmlRow(id);
        requireDisableable(id, row.name());
        rows.dispose(id); // 未装载（已停用）行：点名，页面按状态出按钮
        setDisabled(id, true);
    }

    /** 启用：装配行翻回 + 运行期重建装载（插件包行重建类加载器）。 */
    public void enable(String id) {
        RowModel row = requireYmlRow(id);
        requireDisableable(id, row.name());
        if (runtimeRow(id) == null) {
            loadFromRow(row);
        }
        setDisabled(id, false);
    }

    /** 卸载：运行期拔除 + 装配行删除（彻底退场，扫描可再发现同包）。 */
    public void uninstall(String id) {
        requireYmlRow(id);
        if (runtimeRow(id) != null) {
            rows.dispose(id);
        }
        mutateYml(rowsNow -> {
            rowsNow.removeIf(row -> id.equals(row.id()));
            return rowsNow;
        });
    }

    /** 停/启是否运行期可用（不可拔清单内 → 页面出"需重启生效"标注）。 */
    public boolean disableable(String id) {
        RowModel row = findYmlRow(id);
        return row == null || !UNDISABLEABLE.contains(row.name());
    }

    /** 行状态合并视图：运行期行（六态）+ 装配文件独有行（disabled 等，state "-"）。 */
    public List<CenterRow> status() {
        Map<String, CenterRow> merged = new LinkedHashMap<>();
        for (RowSnapshot snapshot : rows.rows()) {
            merged.put(snapshot.id(), new CenterRow(snapshot.id(), snapshot.pluginName(),
                    snapshot.state().name(), false, null, true, null));
        }
        for (RowModel row : readRows()) {
            boolean disableable = !UNDISABLEABLE.contains(row.name());
            String configJson = row.config() == null ? null : row.config().toString();
            if (merged.containsKey(row.id())) {
                CenterRow live = merged.get(row.id());
                merged.put(row.id(), new CenterRow(live.id(), live.pluginName(), live.state(),
                        false, row.jar(), disableable, configJson));
            } else {
                // yml 独有行：disabled 取装配行真实标记——"启用却不在运行期"是坏载
                // 信号（state "-" 且 disabled=false），不吞成"已停用"
                merged.put(row.id(), new CenterRow(row.id(), row.name(), "-", row.disabled(),
                        row.jar(), disableable, configJson));
            }
        }
        return List.copyOf(merged.values());
    }

    /**
     * 挂载类路径插件为新行（换审批策略等"用内置实现替换现行行"的通道）：
     * 类从宿主 classpath 反射实例化，装载 + 装配落行与 {@link #install} 同纪律
     * （写回失败回滚装载）。重复 id 由行级控制口点名拒绝——先卸现行行再挂。
     */
    public void installClasspath(String id, String fqcn, Map<String, Object> config) {
        requireId(id);
        Objects.requireNonNull(fqcn, "fqcn");
        Plugin<?> plugin;
        try {
            plugin = (Plugin<?>) Class.forName(fqcn).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new PluginException("类不可加载或不可实例化: " + fqcn + "（须在宿主 classpath 上）", e);
        }
        rows.load(id, plugin, config);
        try {
            mutateYml(rowsNow -> {
                rowsNow.add(new RowModel(id, fqcn, null,
                        config == null ? null : yaml.valueToTree(config), false));
                return rowsNow;
            });
        } catch (RuntimeException e) {
            rows.dispose(id);
            throw e;
        }
    }

    /**
     * 改配置（"停→改→启"向导的服务端一步）：运行期拔除 → 装配行 config 替换 →
     * 按新配置重建装载。不可拔行点名"需重启生效"。
     */
    public void reconfigure(String id, Map<String, Object> config) {
        RowModel row = requireYmlRow(id);
        requireDisableable(id, row.name());
        rows.dispose(id); // 未装载（已停用）行：点名，页面按状态出按钮
        mutateYml(rowsNow -> {
            rowsNow.replaceAll(r -> id.equals(r.id())
                    ? new RowModel(r.id(), r.name(), r.jar(),
                            config == null ? null : yaml.valueToTree(config), false)
                    : r);
            return rowsNow;
        });
        loadFromRow(new RowModel(row.id(), row.name(), row.jar(),
                config == null ? null : yaml.valueToTree(config), false));
    }

    // === 内部：运行期重建 ===

    private RowSnapshot runtimeRow(String id) {
        return rows.rows().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
    }

    /** 按装配行重建运行期实例（插件包行重建类加载器；classpath 行反射）。 */
    private void loadFromRow(RowModel row) {
        try {
            if (row.jar() != null && !row.jar().isBlank()) {
                PluginJarClassLoader loader = PluginJarClassLoader.open(Path.of(row.jar()));
                try {
                    Plugin<?> plugin = (Plugin<?>) loader.loadPluginClass(row.name())
                            .getDeclaredConstructor().newInstance();
                    try {
                        rows.load(row.id(), plugin, row.config(), loader);
                        return;
                    } catch (RuntimeException e) {
                        closeQuietly(loader);
                        throw e;
                    }
                } catch (PluginException e) {
                    throw e;
                } catch (Exception | LinkageError e) {
                    closeQuietly(loader);
                    throw new PluginException("插件行重建失败: " + row.id() + "（jar: " + row.jar()
                            + "；原始错误: " + e + "）", e);
                }
            }
            Plugin<?> plugin = (Plugin<?>) Class.forName(row.name())
                    .getDeclaredConstructor().newInstance();
            rows.load(row.id(), plugin, row.config());
        } catch (ReflectiveOperationException e) {
            throw new PluginException("插件行重建失败: " + row.id() + "（类 " + row.name()
                    + " 不可加载或不可实例化）", e);
        }
    }

    // === 内部：不可拔与 id 纪律 ===

    private void requireDisableable(String id, String pluginName) {
        if (UNDISABLEABLE.contains(pluginName)) {
            throw new PluginException("插件 " + id + "（" + pluginName
                    + "）不可运行期停/启——需重启生效（请编辑装配文件后重启进程）");
        }
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new PluginException("行 id 不能为空");
        }
    }

    // === 内部：装配文件（唯一事实源；结构化重写不保注释——既定口径） ===

    private Path assemblyFile() {
        return DuoHome.resolve().root().resolve(BootLoader.USER_ASSEMBLY_FILE);
    }

    private Path pluginDir() {
        return DuoHome.resolve().resolveDir(PLUGINS_DIR);
    }

    private RowModel requireYmlRow(String id) {
        RowModel row = findYmlRow(id);
        if (row == null) {
            throw new PluginException("装配文件中无行 \"" + id + "\"（操作只及在册行）");
        }
        return row;
    }

    private RowModel findYmlRow(String id) {
        return readRows().stream().filter(r -> id.equals(r.id())).findFirst().orElse(null);
    }

    private void setDisabled(String id, boolean disabled) {
        mutateYml(rowsNow -> {
            rowsNow.replaceAll(row -> id.equals(row.id())
                    ? new RowModel(row.id(), row.name(), row.jar(), row.config(), disabled)
                    : row);
            return rowsNow;
        });
    }

    /** 装配文件读行（宽容解析：未知字段跳过——与 boot 同一口径）。 */
    private List<RowModel> readRows() {
        Path file = assemblyFile();
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            Assembly parsed = yaml.readValue(Files.readAllBytes(file), Assembly.class);
            return parsed == null || parsed.plugins() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(parsed.plugins());
        } catch (IOException e) {
            throw new PluginException("装配文件解析失败: " + file + "（" + e.getMessage() + "）", e);
        }
    }

    /** 装配文件改写：解析 → 变更 → 原子写（临时文件 + 原子改名，不半写）。 */
    private synchronized void mutateYml(java.util.function.UnaryOperator<List<RowModel>> change) {
        List<RowModel> current = readRows();
        List<RowModel> next = change.apply(current);
        Path file = assemblyFile();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            yaml.writeValue(tmp.toFile(), new Assembly(next));
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file);
            }
        } catch (IOException e) {
            throw new PluginException("装配文件写回失败: " + file + "（" + e.getMessage() + "）", e);
        }
    }

    /** 装配文件顶层结构（与 boot 的行结构同形）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Assembly(List<RowModel> plugins) {
    }

    /** 装配行（与 boot 行结构同形：id/name/jar/config/disabled）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RowModel(String id, String name, String jar, JsonNode config, boolean disabled) {
    }

    // === 内部：杂项 ===

    private static String normalize(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    private static boolean referencesPluginInterface(byte[] classBytes) {
        byte[] marker = PLUGIN_INTERFACE_MARKER.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + marker.length <= classBytes.length; i++) {
            int j = 0;
            while (j < marker.length && classBytes[i + j] == marker[j]) {
                j++;
            }
            if (j == marker.length) {
                return true;
            }
        }
        return false;
    }

    /** jar 条目路径 → FQCN（dev/duo/x/Foo.class → dev.duo.x.Foo；内部类 $ 保留）。 */
    private static String classNameOf(String entryName) {
        return entryName.substring(0, entryName.length() - ".class".length())
                .replace('/', '.');
    }

    private static void closeQuietly(AutoCloseable closer) {
        try {
            closer.close();
        } catch (Exception e) {
            // 关闭失败 = 加载器泄漏，兜底口径"需重启生效"——不掩盖主错误
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用（JDK 必备算法）", e);
        }
    }
}
