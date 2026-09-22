package dev.duo.harness.example;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.boot.Boot;

import java.nio.file.Path;
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
     * 进程退出码即成败契约（解析 {@link dev.duo.harness.example.headless.HeadlessArgs}）。
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
        var parsed = dev.duo.harness.example.headless.HeadlessArgs.parse(args);
        if (parsed.headless()) {
            if (onRootCreated != null) {
                System.err.println("[headless] --json 模式不支持编程挂载回调，onRootCreated 已忽略");
            }
            if (!parsed.errors().isEmpty()) {
                parsed.errors().forEach(e -> System.err.println("[headless] " + e));
                System.err.println("用法: DuoMain --json [--session-id <id>] [装配.yml] <任务文本...>");
                System.exit(2);
            }
            System.exit(dev.duo.harness.example.headless.HeadlessBoot.run(
                    parsed.effectiveYml(), parsed.sessionId(), parsed.prompt()));
        }
        Path yml = args.length > 0 ? Path.of(args[0])
                : Path.of(DuoMain.class.getResource(
                        dev.duo.harness.example.headless.HeadlessArgs.DEFAULT_YML_RESOURCE).toURI());
        Context root = Boot.from(yml);
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
