package dev.duo.harness.tools.fs;

/**
 * 后台任务（M23 工单 04，ADR-0025 决策二）：bash {@code run_in_background} 启动的
 * 进程级执行单元——内存注册表持有，进程独立于 turn 存活（暂停不杀、进程退出全灭）。
 *
 * <p>双流并发读（前台 bash 同款，{@link FsBashTool.StreamCapture} 复用）：读线程
 * 持续排空管道，输出尾部窗口随取。终止复用前台杀树（子孙先 SIGTERM、宽限后
 * SIGKILL）。{@link #notice()} 供完成通知路由（first-wins——settle 即发，每任务
 * 至多一条）。</p>
 */
public final class BackgroundTask {

    /** 生命周期状态：运行中 / 正常退出 / 被终止。 */
    public enum State { RUNNING, EXITED, KILLED }

    private final String taskId;
    private final String command;
    private final Process process;
    private final FsBashTool.StreamCapture stdout = new FsBashTool.StreamCapture();
    private final FsBashTool.StreamCapture stderr = new FsBashTool.StreamCapture();

    private volatile State state = State.RUNNING;
    private volatile int exitCode = -1;

    BackgroundTask(String taskId, String command, Process process) {
        this.taskId = taskId;
        this.command = command;
        this.process = process;
        Thread.ofVirtual().name("bg-out-" + taskId)
                .start(() -> FsBashTool.capture(process.getInputStream(), stdout));
        Thread.ofVirtual().name("bg-err-" + taskId)
                .start(() -> FsBashTool.capture(process.getErrorStream(), stderr));
    }

    /** 等待进程退出（注册表监视线程调用；被 stop 的杀树后此处正常返回）。 */
    void awaitExit() {
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return; // 注册表关闭路径：监视线程退出，状态由调用方定
        }
        // terminate 先置 KILLED 时杀树导致的正常退出不改状态（终态 first-wins）
        if (state == State.RUNNING) {
            state = State.EXITED;
        }
    }

    /** 终止任务（task-stop / 注册表关闭）：杀进程树并置 KILLED（幂等——已结束原样返回）。 */
    void terminate() {
        if (state != State.RUNNING) {
            return;
        }
        state = State.KILLED;
        process.destroy();
        FsBashTool.terminateTree(process);
    }

    /** 任务是否已到终态（EXITED / KILLED）。 */
    public boolean isCompleted() {
        return state != State.RUNNING;
    }

    /** 进程句柄（包内：task-output 的 block 等待复用 waitFor）。 */
    Process process() {
        return process;
    }

    /** 输出全文（stdout 在前、stderr 带 [stderr] 标题缀后；空流不留痕）。 */
    public String output() {
        StringBuilder sb = new StringBuilder(stdout.text());
        if (!stderr.text().isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("[stderr]\n").append(stderr.text());
        }
        return sb.toString();
    }

    /** 终态描述行（通知与 task-output 的状态行共用）。 */
    public String terminalLine() {
        return switch (state) {
            case EXITED -> "[exit code: " + exitCode + "]";
            case KILLED -> "[已终止] 进程树被 task-stop / 注册表关闭终止";
            case RUNNING -> "[运行中]";
        };
    }

    /** 完成通知文本（first-wins，每任务至多一条）。 */
    public String notice() {
        return "[后台任务完成] " + taskId + "：" + command + "\n" + terminalLine();
    }

    public String taskId() { return taskId; }
    public String command() { return command; }
    public State state() { return state; }
    public int exitCode() { return exitCode; }
}
