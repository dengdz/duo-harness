package dev.duo.harness.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.events.PluginStatus;
import dev.duo.harness.example.tools.EchoToolPlugin;
import dev.duo.harness.example.tools.ToolsView;
import dev.duo.harness.mcp.McpClientPlugin;
import dev.duo.harness.tools.ToolNotFoundException;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsService;

import java.io.PrintStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * duo-harness Demo 入口：一条命令演示 M1 + M2 全链路——
 * M1：配置驱动 boot（含 disabled 行与行序无关）、服务注入（视图寻址）、
 * 工具三段管线（准入否决与结果治理）、依赖驱动生命周期（拔服务级联停止）。
 * M2：MCP 连接（迷你 filesystem server，真实 stdio 协议 + 真实文件读写）、
 * 远端工具同步、审批策略拒绝、guard 单调否决、拔连接后工具消失。
 *
 * <p>运行：{@code mvn -pl duo-harness-example -am package exec:java}</p>
 */
public final class DemoMain {

    /** demo 叙述通道的事件名（插件发、main 收，共用约定）。 */
    public static final String DEMO_LOG_CHANNEL = "demo/log";

    /** 级联演示的临时服务名。 */
    static final String TEMP_SERVICE_NAME = "temp-news";

    /** M2 迷你 filesystem 服务器的 FQCN（MCP 子进程主类）。 */
    private static final String MINI_FS_SERVER_NAME =
            "dev.duo.harness.example.mcpfs.MiniFileSystemServer";

    private DemoMain() {
    }

    /** 输出面向演示终端，是本类的功能而非调试残留。 */
    public static void main(String[] args) throws Exception {
        Path yml = Path.of(DemoMain.class.getResource("/demo.yml").toURI());
        run(yml, System.out);
    }

    /** 可测入口（冒烟测试经它断言输出叙述）。 */
    static void run(Path yml, PrintStream out) throws Exception {

        m1Act(yml, out);

        m2Act(out);
    }

    private static void m1Act(Path yml, PrintStream out) throws Exception {
        out.println("=== duo-harness M1 Demo：插件化全链路 ===");
        out.println("[boot] 读取 " + yml.getFileName());

        Context root = bootWithNarration(yml, out);
        out.println("[boot] 插件树激活完成（demo-disabled 行在场而实例未装载）");

        ToolsService tools = root.as(ToolsView.class).tools();
        JsonMapper json = JsonMapper.builder().build();

        out.println("[工具] 正常执行:");
        printResult(out, tools.execute(EchoToolPlugin.TOOL_NAME, json.createObjectNode().put("input", "世界")));

        out.println("[工具] 准入否决（参数含敏感词）:");
        printResult(out, tools.execute(EchoToolPlugin.TOOL_NAME,
                json.createObjectNode().put("input", "危险操作")));

        out.println("[工具] post-execute 结果治理:");
        printResult(out, tools.execute(EchoToolPlugin.TOOL_NAME,
                json.createObjectNode().put("input", "第二次调用")));

        out.println("[级联] 运行时挂临时服务提供者与消费者，再拔掉提供者:");
        PluginHandle provider = root.plugin(new TempProviderPlugin(), null);
        provider.awaitStartup();
        PluginHandle consumer = root.plugin(new TempConsumerPlugin(), null);
        consumer.awaitStartup();
        provider.dispose();
        out.println("[级联] 提供者已停——消费者经 UNLOADING 回到 PENDING（见上方状态叙述）");

        root.dispose();
        out.println("=== Demo 结束（整树已回滚） ===");
    }

    /** 带状态与 demo 叙述监听器的 boot（M1/M2 两段共用叙述风格）。 */
    private static Context bootWithNarration(Path yml, PrintStream out) throws Exception {
        return Boot.from(yml, ctx -> {
            ctx.on(PluginStatus.EVENT, event -> {
                PluginStatus status = (PluginStatus) event;
                out.println("  [状态] " + simpleName(status.plugin())
                        + ": " + status.from() + " -> " + status.to());
                return null;
            });
            ctx.on(DEMO_LOG_CHANNEL, message -> {
                out.println("  [demo] " + message);
                return null;
            });
        });
    }

    private static void printResult(PrintStream out, ToolResult result) {
        String flag = result.isError() ? "错误" : "正常";
        out.println("  -> [" + flag + "] " + result.value());
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    // === M2 段落：MCP 连接 + 治理链 ===

    static void m2Act(PrintStream out) throws Exception {
        out.println();
        out.println("=== duo-harness M2 Demo：MCP 连接与治理链 ===");

        // 1. 真实临时目录 + 真实文件
        Path rootDir = Files.createTempDirectory("duo-m2-demo");
        Path notes = rootDir.resolve("notes.txt");
        Files.writeString(notes, "M2 演示：这是 notes.txt 的真实内容");
        Files.writeString(rootDir.resolve("secret.txt"), "绝密内容（guard 应拦截读取）");
        out.println("[M2] 临时目录就绪: " + rootDir.getFileName() + "（notes.txt / secret.txt 已写入磁盘）");

        // 2. boot 治理配置（tools + 审批 always-deny + 写保护治理插件）
        Path m2Yml = Path.of(DemoMain.class.getResource("/demo-m2.yml").toURI());
        out.println("[M2] boot " + m2Yml.getFileName() + "（tools / 审批 always-deny / 写保护）");
        Context root = bootWithNarration(m2Yml, out);

        // 3. 挂载 MCP 连接（等价于 demo.yml 一行 MCP 配置，见 demo-m2.yml 注释）
        JsonNode mcpConfig = mcpConfig(rootDir);
        out.println("[M2] 挂载 MCP 连接（等价 yml 行: serverName=files, "
                + "command=java, args=[-cp, <classpath>, MiniFileSystemServer, " + rootDir.getFileName() + "]）:");
        ToolsService tools = root.as(ToolsView.class).tools();
        PluginHandle files = root.plugin(new McpClientPlugin(), mcpConfig);
        files.awaitStartup();
        out.println("  [M2] 连接状态: " + files.state() + "（远端工具已自动同步进工具域）");

        // 4. 远端工具读真实文件
        out.println("[M2] read_file(notes.txt)——经三段管线调用远端 filesystem server:");
        printResult(out, tools.execute("mcp__files__read_file",
                JsonNodeFactory.instance.objectNode().put("path", "notes.txt")));

        // 5. guard 治理：涉密文件拒绝、普通文件放行
        out.println("[M2] read_file(secret.txt)——guard 治理（涉密拦截）:");
        printResult(out, tools.execute("mcp__files__read_file",
                JsonNodeFactory.instance.objectNode().put("path", "secret.txt")));

        // 6. 审批拒绝：write_file 被声明 ask，always-deny 拒绝
        out.println("[M2] write_file——写操作被声明需审批，always-deny 策略拒绝:");
        printResult(out, tools.execute("mcp__files__write_file",
                JsonNodeFactory.instance.objectNode()
                        .put("path", "injected.txt").put("content", "不应写入成功")));

        // 7. 拔连接 → 工具消失
        out.println("[M2] dispose MCP 连接:");
        files.dispose();
        try {
            tools.execute("mcp__files__read_file",
                    JsonNodeFactory.instance.objectNode().put("path", "notes.txt"));
            out.println("  -> [异常] 工具仍在册（不应发生）");
        } catch (ToolNotFoundException e) {
            out.println("  -> [消失] " + e.getMessage());
        }

        root.dispose();
        out.println("=== M2 Demo 结束（整树已回滚） ===");
        out.flush();
    }

    /** MCP 连接配置（等价 demo-m2.yml 注释中的 yml 行）。 */
    private static JsonNode mcpConfig(Path rootDir) {
        var config = JsonNodeFactory.instance.objectNode();
        config.put("serverName", "files");
        config.put("command", javaCommand());
        config.put("requestTimeoutMs", 5_000);
        var reconnect = config.putObject("reconnect");
        reconnect.put("initialDelayMs", 100);
        reconnect.put("maxDelayMs", 500);
        reconnect.put("maxAttempts", 3);
        var args = config.putArray("args");
        args.add("-cp").add(subprocessClasspath())
                .add(MINI_FS_SERVER_NAME)
                .add(rootDir.toString());
        return config;
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * 子进程 classpath：优先 java.class.path（IDEA / 裸 java 启动时含全部类与依赖）；
     * exec:java（maven 同 JVM）下该属性是 maven 自身的，退而从 context classloader
     * 枚举 URL（ClassRealm 可枚举项目类与依赖 jar）。
     */
    public static String subprocessClasspath() {
        String property = System.getProperty("java.class.path");
        if (property != null && property.contains("duo-harness")) {
            return property;
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        List<URI> urls = new ArrayList<>();
        while (cl != null) {
            if (cl instanceof URLClassLoader urlLoader) {
                for (URL url : urlLoader.getURLs()) {
                    urls.add(URI.create(url.toExternalForm()));
                }
            }
            cl = cl.getParent();
        }
        if (urls.isEmpty()) {
            throw new IllegalStateException("无法探测子进程 classpath（java.class.path 与"
                    + " context classloader URL 均不可用）");
        }
        return urls.stream()
                .filter(uri -> "file".equals(uri.getScheme()))
                .map(uri -> Path.of(uri).toString())
                .collect(Collectors.joining(java.io.File.pathSeparator));
    }

    /** 级联演示用临时提供者：发布 temp-news 服务。 */
    static final class TempProviderPlugin implements Plugin<Void> {
        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            return ctx.provide(TEMP_SERVICE_NAME, "临时消息");
        }
    }

    /** 级联演示用临时消费者：依赖 temp-news。 */
    static final class TempConsumerPlugin implements Plugin<Void> {
        @Override
        public Set<String> inject() {
            return Set.of(TEMP_SERVICE_NAME);
        }

        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            ctx.emit(DEMO_LOG_CHANNEL, "临时消费者激活，读到服务: " + ctx.hasService(TEMP_SERVICE_NAME));
            return null;
        }
    }
}
