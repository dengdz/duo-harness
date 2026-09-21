package dev.duo.harness.tools.fs;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 后台任务注册表（M23 工单 04，ADR-0025 决策二）：内存持有本进程的全部
 * {@link BackgroundTask}——taskId 索引、状态查询、终止与关闭全杀。经
 * {@link FsToolsPlugin} 以 {@link #SERVICE_NAME} 发布为服务，呈现位（CLI/Web）
 * 注册完成通知监听器路由（first-wins：每任务 settle 时至多通知一次——通知语义
 * 必达不重复；监听器须在任务 settle 前注册，迟到的监听器收不到既有终态）。
 *
 * <p>生命周期：随进程存活；{@link FsToolsPlugin} 停止时 {@link #shutdownAll()}
 * 杀全部进程树（树终止防孤儿孙进程）——插件树 dispose 级联到达。</p>
 */
public final class BackgroundTaskRegistry {

    public static final String SERVICE_NAME = "backgroundTasks";

    private final ConcurrentLinkedQueue<BackgroundTask> tasks = new ConcurrentLinkedQueue<>();
    private final CopyOnWriteArrayList<Consumer<BackgroundTask>> listeners =
            new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger seq =
            new java.util.concurrent.atomic.AtomicInteger();
    private final BashOutputConfig outputConfig;
    private final java.nio.file.Path spillDir;

    /** 缺省预算构造（spill 落 Duo home 临时区）。 */
    public BackgroundTaskRegistry() {
        this(BashOutputConfig.DEFAULTS);
    }

    /** 指定预算构造（fs-tools 行 config 的 output 段，M23 工单 05）。 */
    public BackgroundTaskRegistry(BashOutputConfig outputConfig) {
        this.outputConfig = outputConfig == null ? BashOutputConfig.DEFAULTS : outputConfig;
        this.spillDir = dev.duo.harness.core.api.boot.DuoHome.resolve()
                .resolveDir("tmp/bash-spill");
    }

    /**
     * 启动后台任务：接管进程（双流读 + 完成监视线程）并注册——调用方只管把
     * {@code ProcessBuilder.start()} 的产物交进来。完成时逐个通知监听器（first-wins）。
     */
    public BackgroundTask start(Process process, String command) {
        BackgroundTask task = new BackgroundTask("bg-" + seq.incrementAndGet(), command, process,
                outputConfig, spillDir);
        tasks.add(task);
        Thread.ofVirtual().name("bg-watch-" + task.taskId()).start(() -> {
            task.awaitExit();
            if (!task.isCompleted()) {
                return; // awaitExit 被打断提前返回：不发假「运行中」通知烧掉 first-wins
            }
            for (Consumer<BackgroundTask> listener : listeners) {
                try {
                    listener.accept(task);
                } catch (RuntimeException e) {
                    // 监听器异常隔离（Context.emit 同约定）：单个路由方抛错不饿死余者
                }
            }
        });
        return task;
    }

    /** 按 taskId 查询。 */
    public Optional<BackgroundTask> get(String taskId) {
        return tasks.stream().filter(t -> t.taskId().equals(taskId)).findFirst();
    }

    /** 全部任务（含终态——可见化与 task-output 的「已结束」查询）。 */
    public List<BackgroundTask> all() {
        return List.copyOf(tasks);
    }

    /** 注册完成通知监听器（呈现位路由：idle 开新轮 / busy 挂 next-turn）。 */
    public void addListener(Consumer<BackgroundTask> listener) {
        listeners.add(listener);
    }

    /** 摘除全部监听器（注册表关闭路径：shutdown 杀树的通知不再路由进拆树中的呈现位）。 */
    public void clearListeners() {
        listeners.clear();
    }

    /** 关闭全杀：插件树停止/进程退出路径——全部运行中任务杀进程树（幂等）。 */
    public void shutdownAll() {
        for (BackgroundTask task : tasks) {
            task.terminate();
        }
    }

    /** 全部任务刷 spill 缓冲（关闭清理前调用，防回读缺尾）。 */
    public void finishAllSpills() {
        for (BackgroundTask task : tasks) {
            task.finishSpill();
        }
    }
}
