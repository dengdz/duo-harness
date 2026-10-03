package dev.duo.harness.example;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.boot.Boot;
import dev.duo.harness.example.headless.HeadlessArgs;
import dev.duo.harness.example.headless.HeadlessBoot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 通用启动器（ADR-0011）：Boot 装载插件树 + 非守护等待 + shutdown hook 级联
 * dispose——只负责把树跑起来并保活，不含任何业务装配（demo 的 MCP 连接等
 * 挂载经 {@code run} 的回调注入）。呈现位（CliPlugin / WebPlugin）以 yml 行
 * 启停（行缺席或 {@code disabled: true} 即关闭）。
 *
 * <p>纯 Web 部署的 JVM 存活由本类的非守护等待保证；Ctrl-C / SIGTERM 触发
 * shutdown hook → 级联 dispose → 全部会话独占锁确定性释放。</p>
 */
public final class DuoMain {

    private DuoMain() {
    }

    public static void main(String[] args) throws Exception {
        run(args, root -> { });
    }

    /**
     * 行序契约预检（M27 工单 05，扫描册 H-05）：cli 行的 apply 即 REPL 主循环——
     * 其后所有行在 REPL 退出前不会装载，web 行后置 = Web 呈现位静默缺席（M27 工单 05 实测）。cli 与 web 两行同时在册时
     * web 必须在前，违例 boot 前即点名。纯单呈现位 / 无呈现位装配零感。仅识别标准行
     * 形态（strip 后全等）；行尾注释/引号变体不识别——漏检由 web 装配期 fail-fast 兜底。
     */
    static void validatePresenterRowOrder(Path yml) throws IOException {
        validatePresenterRowOrder(Files.readAllLines(yml));
    }

    /** 资源流形态（缺省装配分支）：classpath 单次读全量后行扫描，与文件形态同一扫描核；
     * 资源缺失抛 IOException——由 run() 的预检 catch 网兜住，与文件分支同走「消息 + exit 2」。 */
    static void validatePresenterRowOrder(String resourcePath) throws IOException {
        try (var in = DuoMain.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("缺省装配资源缺失: " + resourcePath);
            }
            validatePresenterRowOrder(new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().toList());
        }
    }

    private static void validatePresenterRowOrder(List<String> lines) {
        int cliIdx = -1;
        int webIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if ("- id: cli".equals(line) && cliIdx < 0 && !hasDisabledFlag(lines, i)) {
                cliIdx = i;
            } else if ("- id: web".equals(line) && webIdx < 0 && !hasDisabledFlag(lines, i)) {
                webIdx = i;
            }
        }
        if (cliIdx >= 0 && webIdx >= 0 && webIdx > cliIdx) {
            throw new IllegalArgumentException("行序契约：web 行必须先于 cli 行——cli 行的 apply"
                    + " 即 REPL 主循环，其后的行在 REPL 退出前不会装载，web 行后置将静默缺席"
                    + "（yml 第 " + (cliIdx + 1) + " 行 cli / 第 " + (webIdx + 1) + " 行 web）。"
                    + "请将 web 行移至 cli 行之前。");
        }
    }

    /**
     * id 行之后紧邻的 disabled: true 标记（≤3 行内、遇空行或下一 id 行即止）——
     * 停用行不参与行序契约（停用的 cli 不阻塞其后行装载）。
     */
    private static boolean hasDisabledFlag(List<String> lines, int idIdx) {
        for (int i = idIdx + 1; i <= Math.min(idIdx + 3, lines.size() - 1); i++) {
            String stripped = lines.get(i).strip();
            if (stripped.isEmpty() || stripped.startsWith("- id:")) {
                break;
            }
            if (stripped.equals("disabled: true")) {
                return true;
            }
        }
        return false;
    }

    /** 树启动后的挂载回调（允许受检异常——演示装配含文件 IO）。 */
    @FunctionalInterface
    public interface ContextConsumer {

        void accept(Context root) throws Exception;
    }

    /**
     * 启动并保活：默认装载 agent-demo.yml，也可经 {@code args[0]} 指定 yml 路径。
     *
     * <p>headless 模式（M23 工单 07）：{@code --json} 在场时按一次性任务驱动——
     * positional 任务文本 + 可选 {@code --session-id}，stdout 输出 NDJSON 事件流，
     * 进程退出码即成败契约（解析 {@link HeadlessArgs}）。
     * 无任务文本等 usage 错误以退出码 2 明确失败（stderr 说明）。</p>
     *
     * <p>失败模式：{@code onRootCreated} 抛出时整树兜底 dispose 后异常上抛
     * （呈现位 FAILED 场景，会话锁随树释放）；信号触发的 hook 同样先 dispose
     * 再放行——两路收尾都幂等。</p>
     *
     * @param args           headless：{@code --json [--session-id id] [yml路径] 任务文本...}；
     *                       常驻（现状）：{@code args[0]} 可选 yml 路径（缺省 agent-demo.yml 资源）
     * @param onRootCreated  树启动后、呈现位交互前的挂载回调（demo 的 MCP 连接走此入口；
     *                       headless 模式不支持编程挂载）
     * @throws Exception     Boot 装载失败或挂载回调失败（原样上抛）
     */
    public static void run(String[] args, ContextConsumer onRootCreated) throws Exception {
        var parsed = HeadlessArgs.parse(args);
        if (parsed.headless()) {
            if (onRootCreated != null) {
                System.err.println("[headless] --json 模式不支持编程挂载回调，onRootCreated 已忽略");
            }
            if (!parsed.errors().isEmpty()) {
                parsed.errors().forEach(e -> System.err.println("[headless] " + e));
                System.err.println("用法: DuoMain --json [--session-id <id>] [装配.yml] <任务文本...>");
                System.exit(2);
            }
            if (parsed.useDefaultYml()) {
                System.exit(HeadlessBoot.runDefault(parsed.sessionId(), parsed.prompt()));
            }
            System.exit(HeadlessBoot.run(parsed.yml(), parsed.sessionId(), parsed.prompt()));
        }
        Path yml = args.length > 0 ? Path.of(args[0]) : null;
        Path userAssembly = null;
        try {
            if (yml != null) {
                validatePresenterRowOrder(yml);
            } else {
                // 缺省装配分支：用户装配文件在位（缺失则原子物化种子，ADR-0037 工单 03）
                // ——预检与装载同读这份有效文件（编辑物化文件即改装配，行序违例同样被预检拦住）
                userAssembly = Boot.ensureUserAssembly(HeadlessArgs.DEFAULT_YML_RESOURCE);
                validatePresenterRowOrder(userAssembly);
            }
        } catch (IllegalArgumentException | IOException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        }
        Context root = yml != null ? Boot.from(yml)
                : Boot.from(userAssembly);
        CountDownLatch stopped = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            try {
                root.dispose();
            } finally {
                stopped.countDown(); // dispose 抛错也必须放行 main（防永久挂起）
            }
        }, "duo-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            onRootCreated.accept(root);
            System.out.println("[duo] 插件树已启动，Ctrl-C 停止。");
            stopped.await(); // 非守护 main：保活至停树信号
        } finally {
            root.dispose(); // 兜底（幂等）：回调失败或信号竞态下也释放树与会话锁
        }
    }
}
