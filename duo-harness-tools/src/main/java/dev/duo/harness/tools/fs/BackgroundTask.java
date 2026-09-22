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
    /** 发起呈现位归属（M23 工单 06 验收修正）：{@code "cli"} / {@code "web"}；
     * null = 无呈现位发起（子代理/直调）——呈现位按「本位或 null」过滤，null 双面
     * 可见（可见性优先，防任务静默失踪）。 */
    private final String owner;
    private final Process process;
    private final BashOutputConfig config;
    private final FsBashTool.StreamCapture stdout;
    private final FsBashTool.StreamCapture stderr;

    private volatile State state = State.RUNNING;
    private volatile int exitCode = -1;

    BackgroundTask(String taskId, String command, String owner, Process process,
                   BashOutputConfig config, java.nio.file.Path spillDir) {
        this.taskId = taskId;
        this.command = command;
        this.owner = owner == null || owner.isBlank() ? null : owner;
        this.process = process;
        this.config = config == null ? BashOutputConfig.DEFAULTS : config;
        this.stdout = new FsBashTool.StreamCapture(this.config.inlineTailChars(),
                this.config.spillMaxChars(), spillDir.resolve(taskId + "-stdout.txt"));
        this.stderr = new FsBashTool.StreamCapture(this.config.inlineTailChars(),
                this.config.spillMaxChars(), spillDir.resolve(taskId + "-stderr.txt"));
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
        stdout.finish(); // 收口刷 spill 缓冲（M23 工单 05：不刷则回读缺尾）
        stderr.finish();
    }

    /** spill 收尾（注册表关闭路径：杀树后刷新缓冲，防回读缺尾）。 */
    void finishSpill() {
        stdout.finish();
        stderr.finish();
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

    /** 输出（stdout 尾窗在前、stderr 尾窗缀后）+ spill 回读指引**置尾**（task-output
     * 尾窗裁剪不会裁掉路径——M23 工单 05 审查修复）。 */
    public String output() {
        StringBuilder sb = new StringBuilder(stdout.text());
        if (!stderr.text().isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("[stderr]\n").append(stderr.text());
        }
        if (stdout.hasSpill()) {
            sb.append("\n[stdout spilled] 前 ").append(stdout.spilledChars())
              .append(" 字符已落盘，read 回读全文: ").append(stdout.spillPath());
        }
        if (stderr.hasSpill()) {
            sb.append("\n[stderr spilled] 前 ").append(stderr.spilledChars())
              .append(" 字符已落盘，read 回读全文: ").append(stderr.spillPath());
        }
        if (stdout.spillFull() || stderr.spillFull()) {
            sb.append("\n[spill 超帽] 输出超出 spill 上限停止写入，其后内容未保留——请拆分命令");
        }
        if (stdout.spillFailed() || stderr.spillFailed()) {
            sb.append("\n[spill 落盘失败] 部分输出未能保留——请缩小输出");
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
    /** 发起呈现位（cli / web；null = 子代理等无呈现位发起，双面均可见）。 */
    public String owner() { return owner; }
    public State state() { return state; }
    public int exitCode() { return exitCode; }
}
