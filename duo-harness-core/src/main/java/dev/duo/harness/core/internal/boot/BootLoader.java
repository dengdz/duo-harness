package dev.duo.harness.core.internal.boot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.PluginRows;
import dev.duo.harness.core.api.PluginState;
import dev.duo.harness.core.api.boot.BootException;
import dev.duo.harness.core.api.boot.DuoHome;
import dev.duo.harness.core.internal.ContextImpl;
import dev.duo.harness.core.internal.PluginRowsImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * boot 引导的实现：读配置、解析行、逐行装载、审计与回滚。
 *
 * <p>位于 internal——契约包（api.boot）只留 Boot 薄壳与 BootException；
 * Jackson/YAML/文件 IO/日志等第三方依赖不经契约层渗透给下游模块。
 * public 供 api 包 Boot 静态工厂全限定名委托（List.of() 同款例外）。</p>
 */
public final class BootLoader {

    private static final Logger log = LoggerFactory.getLogger(BootLoader.class);

    /** 解析配置行用（容忍未知字段：行结构的正向兼容）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    /** 配置行（解析产物；id 必填、name 必填；jar 为插件包来源字段，缺省 = 纯 classpath 装载）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PluginRow(String id, String name, String jar, JsonNode config, boolean disabled) {
    }

    /** 顶层结构：plugins: [行...]。 */
    record PluginConfig(List<PluginRow> plugins) {
    }

    /** 已装载行（审计输入）：行 + 插件（取 inject）+ 实例句柄。 */
    private record Loaded(PluginRow row, Plugin<?> plugin, PluginHandle handle) {
    }

    private BootLoader() {
    }

    /** 引导入口（Boot 薄壳委托至此）：文件路径形态。 */
    public static Context from(Path configFile, Consumer<Context> onRootCreated) {
        Objects.requireNonNull(onRootCreated, "onRootCreated");
        String yamlText = readConfig(configFile);
        return from(yamlText, configFile.toString(), onRootCreated);
    }

    /**
     * classpath 资源形态入口（Boot.fromResource 委托至此）——fat-jar 内资源 URI
     * 非文件形态，缺省装配装载的正门（M30 工单 01）。语义与文件形态完全一致：
     * 同一解析、审计点名与失败回滚，仅错误消息以 {@code classpath:<resourcePath>}
     * 标签标识来源。
     */
    public static Context fromResource(String resourcePath, Consumer<Context> onRootCreated) {
        Objects.requireNonNull(onRootCreated, "onRootCreated");
        return from(readResource(resourcePath), "classpath:" + resourcePath, onRootCreated);
    }

    /** 文本装载入口（文件/资源两形态共用同一装载器、语义对齐）：source 仅作错误点名标签。 */
    private static Context from(String yamlText, String source, Consumer<Context> onRootCreated) {
        Objects.requireNonNull(onRootCreated, "onRootCreated");
        List<PluginRow> rows = parseRows(source, yamlText);
        return activate(source, rows, onRootCreated);
    }

    private static String readConfig(Path configFile) {
        try {
            return Files.readString(configFile);
        } catch (IOException e) {
            throw new BootException(BootException.Stage.READ_CONFIG,
                    "读取配置文件失败: " + configFile, e);
        }
    }

    /** classpath 资源读文本：绝对路径（带 / 前缀）经本类类加载器解析，缺失/IO 同 READ_CONFIG 点名。 */
    private static String readResource(String resourcePath) {
        return new String(readResourceBytes(resourcePath), StandardCharsets.UTF_8);
    }

    /** classpath 资源读字节（文本与种子指纹共用；缺失/IO 同 READ_CONFIG 点名）。 */
    private static byte[] readResourceBytes(String resourcePath) {
        try (InputStream in = BootLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new BootException(BootException.Stage.READ_CONFIG,
                        "读取配置资源失败（类路径缺失）: classpath:" + resourcePath);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new BootException(BootException.Stage.READ_CONFIG,
                    "读取配置资源失败: classpath:" + resourcePath, e);
        }
    }

    // === 用户装配（ADR-0037 工单 03：可写事实源） ===

    /** 用户装配文件名（DUO_HOME 根下；物化后为缺省装配的唯一装载来源）。public 供插件中心（M35 工单 05）写回同一定位。 */
    public static final String USER_ASSEMBLY_FILE = "plugins.yml";

    /** 种子指纹文件名（物化时点的种子内容指纹，升级漂移对账锚）。 */
    private static final String SEED_MARK_SUFFIX = ".seed";

    /**
     * 确保用户装配文件在位并返回其路径：{@code DUO_HOME/plugins.yml} 存在即直接
     * 返回（种子资源仅作升级漂移对账参照）；缺失则把种子资源原子物化（临时文件
     * + 原子改名，不半写）后返回。升级漂移（内置种子随版本演进）只记日志提示
     * 对账，不阻断启动。public 供 api 包 Boot.ensureUserAssembly 委托。
     */
    public static Path ensureUserAssembly(String seedResource) {
        Path assembly;
        Path mark;
        try {
            Path home = DuoHome.resolve().root();
            Files.createDirectories(home);
            assembly = home.resolve(USER_ASSEMBLY_FILE);
            mark = home.resolve(USER_ASSEMBLY_FILE + SEED_MARK_SUFFIX);
        } catch (IOException e) {
            throw new BootException(BootException.Stage.READ_CONFIG,
                    "用户装配目录创建失败（duo home）", e);
        }
        if (Files.exists(assembly)) {
            checkSeedDrift(seedResource, mark);
            return assembly;
        }
        byte[] seed = readResourceBytes(seedResource);
        try {
            atomicWrite(assembly, seed);
            Files.writeString(mark, sha256Hex(seed));
        } catch (IOException e) {
            throw new BootException(BootException.Stage.READ_CONFIG,
                    "缺省装配物化失败: " + assembly + "（磁盘/权限）", e);
        }
        log.info("缺省装配已物化: {} ← 种子 {}（此后该文件为唯一装载来源，改文件即改装配；"
                + "升级新增行以启动日志提示对账）", assembly, seedResource);
        return assembly;
    }

    /** 升级漂移对账：物化指纹 ≠ 当前种子指纹时日志提示（新增行不自动出现），不阻断。 */
    private static void checkSeedDrift(String seedResource, Path mark) {
        try {
            String current = sha256Hex(readResourceBytes(seedResource));
            String materializedAt = Files.exists(mark) ? Files.readString(mark).trim() : null;
            if (materializedAt == null || materializedAt.isBlank()) {
                log.info("用户装配在册但缺种子指纹（{} 缺失）——跳过漂移对账", mark);
            } else if (!materializedAt.equals(current)) {
                log.info("内置装配种子已演进（物化指纹 {} ≠ 当前 {}）：新增插件行不会自动出现——"
                        + "对账后删除用户装配文件与指纹文件，重启即重新物化", materializedAt, current);
            }
        } catch (IOException e) {
            log.warn("种子漂移对账失败（不阻断启动）: {}", seedResource, e);
        }
    }

    /** 原子写：临时文件 + 同目录原子改名（不支持原子改名的文件系统回落普通改名）。 */
    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用（JDK 必备算法）", e);
        }
    }

    private static List<PluginRow> parseRows(String source, String yamlText) {
        List<PluginRow> rows;
        try {
            PluginConfig parsed = MAPPER.readValue(yamlText, PluginConfig.class);
            rows = parsed == null || parsed.plugins() == null ? List.of() : parsed.plugins();
        } catch (IOException e) {
            throw new BootException(BootException.Stage.PARSE_CONFIG,
                    "解析配置文件失败（非法 YAML 或结构不符，应有 plugins: [行...]）: "
                            + source, e);
        }
        Set<String> seenIds = new HashSet<>(rows.size() * 2);
        for (PluginRow row : rows) {
            if (row == null) {
                // YAML 列表的杂散 "-" 项会解析为 null：按结构错误报告而非 NPE
                throw new BootException(BootException.Stage.PARSE_CONFIG,
                        "配置列表含空行（杂散 \"-\" 项）: " + source);
            }
            if (row.id() == null || row.id().isBlank()) {
                throw new BootException(BootException.Stage.PARSE_CONFIG,
                        "配置行缺 id（id 是审计点名与层序合成的锚点，必填）: " + row);
            }
            if (!seenIds.add(row.id())) {
                // id 重复 = 审计锚点失效（点名指向两行），按结构错误拒绝
                throw new BootException(BootException.Stage.PARSE_CONFIG,
                        "配置行 id 重复: " + row.id());
            }
            if (row.name() == null || row.name().isBlank()) {
                throw new BootException(BootException.Stage.PARSE_CONFIG,
                        "配置行 [" + row.id() + "] 缺 name（插件类 FQCN）");
            }
            if (row.jar() != null && row.jar().isBlank()) {
                throw new BootException(BootException.Stage.PARSE_CONFIG,
                        "配置行 [" + row.id() + "] jar 字段为空白（插件包路径须为实值，"
                                + "不装插件包请删除该字段）");
            }
        }
        return rows;
    }

    private static Context activate(String source, List<PluginRow> rows,
                                    Consumer<Context> onRootCreated) {
        Context root = Context.root();
        onRootCreated.accept(root);
        ContextImpl rootImpl = (ContextImpl) root;
        // 行级控制服务（ADR-0037 内核受控口一）：装载前发布——装载行可声明依赖它
        // （如插件中心），服务在册即随行激活；编程挂载树（Context.root()）不发布
        root.provide(PluginRows.SERVICE_NAME, PluginRowsImpl.of(rootImpl));
        List<Loaded> loaded = new ArrayList<>(rows.size());
        List<String> problems = new ArrayList<>(rows.size());
        List<Throwable> causes = new ArrayList<>(rows.size());

        for (PluginRow row : rows) {
            if (row.disabled()) {
                continue; // 保留行、不加载：禁用语义即"行在场而实例不在"
            }
            try {
                LoadedPlugin lp = loadPluginInstance(row);
                Plugin<?> plugin = lp.plugin();
                Object rawConfig = row.config();
                PluginHandle handle = root.plugin(plugin, rawConfig);
                // 装载即登记（ADR-0037 行级控制）：行 id → 句柄，运行期可寻可拔；
                // 插件包行随行携带类加载器（拔除时释放）
                rootImpl.registerRow(row.id(), plugin.getClass().getName(), handle, lp.closer());
                loaded.add(new Loaded(row, plugin, handle));
            } catch (ReflectiveOperationException e) {
                problems.add("[" + row.id() + "] 插件类不可加载或不可实例化: " + row.name()
                        + "（须有公共无参构造）");
                causes.add(e);
            } catch (PluginException e) {
                problems.add("[" + row.id() + "] " + e.getMessage());
                causes.add(e);
            }
        }
        audit(root, loaded, problems);

        if (!problems.isEmpty()) {
            rollbackQuietly(root);
            throw new BootException(BootException.Stage.ACTIVATE,
                    "插件树启动失败（" + source + "），" + problems.size() + " 个问题：",
                    problems, causes);
        }
        return root;
    }

    /** 单行装载产物：插件实例 + 随行关闭器（插件包行为其类加载器，classpath 行为 null）。 */
    private record LoadedPlugin(Plugin<?> plugin, AutoCloseable closer) {
    }

    /**
     * 单行装载：classpath 行走手写反射；插件包行（jar: 在场）经
     * {@link PluginJarClassLoader} 自优先装载——类不可加载/构造失败/损坏包
     * 统一转为点名异常（LinkageError 也折进 PluginException，不让 Error 逃过
     * 审计），关闭器随行返回、装载失败时当场释放。
     */
    private static LoadedPlugin loadPluginInstance(PluginRow row) throws ReflectiveOperationException {
        if (row.jar() == null) {
            Object instance = Class.forName(row.name()).getDeclaredConstructor().newInstance();
            return new LoadedPlugin(asPluginOrThrow(row, instance), null);
        }
        PluginJarClassLoader loader = PluginJarClassLoader.open(Path.of(row.jar()));
        try {
            Object instance = loader.loadPluginClass(row.name()).getDeclaredConstructor().newInstance();
            return new LoadedPlugin(asPluginOrThrow(row, instance), loader);
        } catch (Exception | LinkageError e) {
            closeLoaderQuietly(row, loader);
            if (e instanceof ReflectiveOperationException re) {
                throw re;
            }
            if (e instanceof PluginException pe) {
                throw pe;
            }
            throw new PluginException("插件包类不可加载: " + row.name()
                    + "（jar: " + row.jar() + "；原始错误: " + e + "）", e);
        }
    }

    /** 实例类型核验：非 Plugin 实现点名拒绝（调用方审计口径与既有文案一致）。 */
    private static Plugin<?> asPluginOrThrow(PluginRow row, Object instance) {
        if (!(instance instanceof Plugin<?> plugin)) {
            throw new PluginException("类 " + row.name() + " 不是 Plugin 实现");
        }
        return plugin;
    }

    private static void closeLoaderQuietly(PluginRow row, AutoCloseable closer) {
        if (closer == null) {
            return;
        }
        try {
            closer.close();
        } catch (Exception e) {
            log.warn("行 {} 的插件包加载器关闭失败（泄漏时需重启生效兜底）", row.id(), e);
        }
    }

    /** 收尾审计：FAILED 重抛点名、PENDING 点名缺失服务清单。 */
    private static void audit(Context root, List<Loaded> loaded, List<String> problems) {
        for (Loaded entry : loaded) {
            PluginState state = entry.handle().state();
            switch (state) {
                case ACTIVE -> { /* 通过 */ }
                case FAILED -> problems.add("[" + entry.row().id() + "] 启动失败（state=FAILED）: "
                        + describeCause(entry.handle()));
                case PENDING -> problems.add("[" + entry.row().id() + "] 永久等待中（state=PENDING），"
                        + "缺失服务: " + missingServices(root, entry.plugin()));
                default -> problems.add("[" + entry.row().id() + "] 启动后状态异常: " + state);
            }
        }
    }

    /** FAILED 的原始错误经 awaitStartup 取回（重抛捕获为消息）。 */
    private static String describeCause(PluginHandle handle) {
        try {
            handle.awaitStartup();
            return "(无错误信息)";
        } catch (PluginException e) {
            Throwable cause = e.getCause();
            return cause == null ? e.getMessage() : cause.toString();
        }
    }

    /** PENDING 行的缺失依赖点名：inject 声明中未提供的部分。 */
    private static List<String> missingServices(Context root, Plugin<?> plugin) {
        List<String> missing = new ArrayList<>();
        for (String name : plugin.inject()) {
            if (!root.hasService(name)) {
                missing.add(name);
            }
        }
        return missing;
    }

    /** 失败整体回滚：整树 dispose；回滚自身错误只记日志（主错误是启动失败）。 */
    private static void rollbackQuietly(Context root) {
        try {
            root.dispose();
        } catch (PluginException e) {
            log.warn("启动失败后的整树回滚出错（主错误优先）", e);
        }
    }
}
