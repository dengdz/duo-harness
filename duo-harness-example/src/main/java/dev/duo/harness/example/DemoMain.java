package dev.duo.harness.example;

import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.PluginStatus;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsService;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Set;

/**
 * M1 Demo 入口：一条命令演示插件化全链路——
 * 配置驱动 boot（含 disabled 行与行序无关）、服务注入（视图寻址）、
 * 工具三段管线（准入否决与结果治理）、依赖驱动生命周期
 * （拔服务级联停止消费者、状态迁移全程叙述）。
 *
 * <p>运行：{@code mvn -pl duo-harness-example exec:java}</p>
 */
public final class DemoMain {

    /** demo 叙述通道的事件名（插件发、main 收，共用约定）。 */
    public static final String DEMO_LOG_CHANNEL = "demo/log";

    /** 级联演示的临时服务名。 */
    static final String TEMP_SERVICE_NAME = "temp-news";

    private DemoMain() {
    }

    /** 输出面向演示终端，是本类的功能而非调试残留。 */
    public static void main(String[] args) throws Exception {
        Path yml = Path.of(DemoMain.class.getResource("/demo.yml").toURI());
        run(yml, System.out);
    }

    /** 可测入口（冒烟测试经它断言输出叙述）。 */
    static void run(Path yml, PrintStream out) throws Exception {
        out.println("=== duo-harness M1 Demo：插件化全链路 ===");
        out.println("[boot] 读取 " + yml.getFileName());

        Context root = Boot.from(yml, ctx -> {
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

    private static void printResult(PrintStream out, ToolResult result) {
        String flag = result.isError() ? "错误" : "正常";
        out.println("  -> [" + flag + "] " + result.value());
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
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
