package dev.duo.harness.core.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 配置驱动 boot：读取单文件 YAML（plugins.yml 形态），逐行把插件类
 * 经编程 API 装载成插件树，收尾审计——失败行点名（含等待依赖的缺失
 * 服务清单），任何失败整树回滚后抛 {@link BootException}。
 *
 * <p>一次性引导：改配置后重启进程生效；运行时行级热重载属 HMR 范畴，
 * 不在本类职责内。</p>
 *
 * <p>配置行结构（行字段即 spec 决策浓缩形状）：</p>
 * <pre>
 * plugins:
 *   - id: echo-tool          # 稳定标识，审计点名与层序合成的锚点，必填
 *     name: com.x.EchoPlugin  # 插件类 FQCN，须有公共无参构造
 *     config: { ... }         # 任意结构，绑定到插件的 config record
 *     disabled: false         # true = 跳过该行（保留行，不加载实例）
 * </pre>
 */
public final class Boot {

    private static final Logger log = LoggerFactory.getLogger(Boot.class);

    /** 解析配置行用（容忍未知字段：行结构的正向兼容）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    /** 配置行（解析产物；id 必填、name 必填）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PluginRow(String id, String name, JsonNode config, boolean disabled) {
    }

    /** 顶层结构：plugins: [行...]。 */
    record PluginConfig(List<PluginRow> plugins) {
    }

    /** 已装载行（审计输入）：行 + 插件（取 inject）+ 实例句柄。 */
    private record Loaded(PluginRow row, Plugin<?> plugin, PluginHandle handle) {
    }

    private Boot() {
    }

    /**
     * 从配置文件引导插件树。
     *
     * @param configFile YAML 配置文件路径
     * @return 已激活的根 Context（审计通过，树存活）
     * @throws BootException 任何失败（阶段见 {@link BootException.Stage}）；
     *         失败时整树已回滚
     */
    public static Context from(Path configFile) {
        return from(configFile, ctx -> {
        });
    }

    /**
     * 从配置文件引导插件树，根 Context 创建后、任何行装载前回调
     * {@code onRootCreated}（对齐 DSH 的 prepare 语义）——用于提前挂
     * 全局监听器（如 plugin/status 状态叙述）。
     *
     * @throws BootException 同 {@link #from(Path)}
     */
    public static Context from(Path configFile, Consumer<Context> onRootCreated) {
        Objects.requireNonNull(onRootCreated, "onRootCreated");
        String yamlText = readConfig(configFile);
        List<PluginRow> rows = parseRows(configFile, yamlText);
        return activate(configFile, rows, onRootCreated);
    }

    private static String readConfig(Path configFile) {
        try {
            return Files.readString(configFile);
        } catch (IOException e) {
            throw new BootException(BootException.Stage.READ_CONFIG,
                    "读取配置文件失败: " + configFile, e);
        }
    }

    private static List<PluginRow> parseRows(Path configFile, String yamlText) {
        List<PluginRow> rows;
        try {
            PluginConfig parsed = MAPPER.readValue(yamlText, PluginConfig.class);
            rows = parsed == null || parsed.plugins() == null ? List.of() : parsed.plugins();
        } catch (IOException e) {
            throw new BootException(BootException.Stage.PARSE_CONFIG,
                    "解析配置文件失败（非法 YAML 或结构不符，应有 plugins: [行...]）: "
                            + configFile, e);
        }
        for (PluginRow row : rows) {
            if (row == null) {
                // YAML 列表的杂散 "-" 项会解析为 null：按结构错误报告而非 NPE
                throw new BootException(BootException.Stage.PARSE_CONFIG,
                        "配置列表含空行（杂散 \"-\" 项）: " + configFile);
            }
            if (row.id() == null || row.id().isBlank()) {
                throw new BootException(BootException.Stage.PARSE_CONFIG,
                        "配置行缺 id（id 是审计点名与层序合成的锚点，必填）: " + row);
            }
            if (row.name() == null || row.name().isBlank()) {
                throw new BootException(BootException.Stage.PARSE_CONFIG,
                        "配置行 [" + row.id() + "] 缺 name（插件类 FQCN）");
            }
        }
        return rows;
    }

    private static Context activate(Path configFile, List<PluginRow> rows,
                                    Consumer<Context> onRootCreated) {
        Context root = Context.root();
        onRootCreated.accept(root);
        List<Loaded> loaded = new ArrayList<>(rows.size());
        List<String> problems = new ArrayList<>(rows.size());
        List<Throwable> causes = new ArrayList<>(rows.size());

        for (PluginRow row : rows) {
            if (row.disabled()) {
                continue; // 保留行、不加载：禁用语义即"行在场而实例不在"
            }
            try {
                Object instance = Class.forName(row.name()).getDeclaredConstructor().newInstance();
                if (!(instance instanceof Plugin<?> plugin)) {
                    problems.add("[" + row.id() + "] 类 " + row.name() + " 不是 Plugin 实现");
                    continue;
                }
                Object rawConfig = row.config();
                PluginHandle handle = root.plugin(plugin, rawConfig);
                loaded.add(new Loaded(row, plugin, handle));
            } catch (ReflectiveOperationException e) {
                problems.add("[" + row.id() + "] 插件类不可加载或不可实例化: " + row.name()
                        + "（须有公共无参构造）");
                causes.add(e);
            } catch (PluginConfigException e) {
                problems.add("[" + row.id() + "] " + e.getMessage());
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
                    "插件树启动失败（" + configFile + "），" + problems.size() + " 个问题：",
                    problems, causes);
        }
        return root;
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
