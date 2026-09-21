package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * bash 工具：每次调用全新进程（{@code bash -c}，shell 状态不跨调用保留），工作目录
 * 固定 workspace 根，env 硬化（NO_COLOR / TERM=dumb / PAGER=cat——关色与分页，
 * 输出稳定可读）。
 *
 * <p>超时 clamp：模型可传低值（缺省 120s、上限 600s），到点终止**进程树**并以
 * {@code [timed out]} marker 呈现——bash 的子命令链不因父进程死亡自动消失，靶向
 * 清理防孤儿。输出三层（M23 工单 05）：内存尾窗保最近输出、超窗历史懒落盘 spill
 * 文件（回传路径可 read 回读全文）、spill 达帽停写并显式告警（不静默）。命令退出而
 * 流仍被后台进程持有（后台任务未收尾）时输出可能不完整，以 marker 明示而非静默截半。
 * 非零退出**不是错误**：
 * {@code [exit code: N]} marker 进正常结果，退出码交模型自决（ADR-0012）。
 * 基础设施故障（bash 启不来、执行被中断）上抛运行时异常，转错误结果。</p>
 *
 * <p>安全边界：bash 的写范围不受 workspace 约束（无 OS 级沙箱，ADR-0012 明示），
 * 拦截点只有审批——{@code requiresApproval()} 声明 + 档位非 danger 一律 ask。</p>
 */
public final class FsBashTool implements ToolDefinition {

    public static final String NAME = "bash";

    private static final long DEFAULT_TIMEOUT_MS = 120_000;
    private static final long MAX_TIMEOUT_MS = 600_000;
    private final WorkspacePolicy workspace;
    /** 后台任务注册表（M23 工单 04；null = 未装配——run_in_background 请求时报错）。 */
    private final BackgroundTaskRegistry registry;
    /** 输出三层预算（M23 工单 05）：inline 尾窗 / spill 帽 / task-output 尾窗。 */
    private final BashOutputConfig outputConfig;
    /** spill 文件序列（bash-out-<n>-stdout/stderr.txt）。 */
    private static final java.util.concurrent.atomic.AtomicInteger SPILL_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    public FsBashTool(WorkspacePolicy workspace) {
        this(workspace, null, BashOutputConfig.DEFAULTS);
    }

    public FsBashTool(WorkspacePolicy workspace, BackgroundTaskRegistry registry) {
        this(workspace, registry, BashOutputConfig.DEFAULTS);
    }

    public FsBashTool(WorkspacePolicy workspace, BackgroundTaskRegistry registry,
                      BashOutputConfig outputConfig) {
        this.workspace = workspace;
        this.registry = registry;
        this.outputConfig = outputConfig == null ? BashOutputConfig.DEFAULTS : outputConfig;
    }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "执行 shell 命令（bash -c，每次调用全新进程，工作目录为 workspace 根）。"
                + "返回输出与退出码——非零退出不算错误。timeoutMs 指定超时（默认 120s，上限 600s）。"
                + "run_in_background=true 时立即返回任务 id 转后台运行（输出用 task-output 读取、"
                + "task-stop 终止；后台不受 timeoutMs 约束）。";
    }
    @Override public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"type\":\"object\",\"properties\":{"
                + "\"command\":{\"type\":\"string\",\"description\":\"要执行的命令（bash -c 语义）\"},"
                + "\"timeoutMs\":{\"type\":\"number\",\"description\":\"超时毫秒（默认 120000，上限 600000）\"},"
                + "\"run_in_background\":{\"type\":\"boolean\",\"description\":\"true = 转后台立即返回任务 id（task-output/task-stop 管理）\"}"
                + "},\"required\":[\"command\"]}");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Override public boolean requiresApproval() { return true; }

    /** 协作式超时优先：管线上限放宽到本调用 timeoutMs 之上 5s——杀进程树归协作式，管线只兜挂死（ADR-0018）。 */
    @Override public Long pipelineTimeoutMs(JsonNode args) {
        return timeoutFor(args) + 5_000L;
    }

    /** 启动 bash 进程（前后台共用）：workspace 根 + env 硬化 + stdin 空设备。 */
    private Process startProcess(String command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", command);
        builder.directory(workspace.root().toFile());
        Map<String, String> env = builder.environment();
        env.put("NO_COLOR", "1");
        env.put("TERM", "dumb");
        env.put("PAGER", "cat");
        // stdin 接空设备：管道无人写即挂起到超时，继承父进程 stdin 则会偷吃 REPL 输入
        builder.redirectInput(new java.io.File("/dev/null"));
        return builder.start();
    }

    /** 后台分支：启动即注册返回 taskId——进程独立存活，输出由注册表双流读持续积累。 */
    private String executeInBackground(String command) {
        if (registry == null) {
            return error("后台任务注册表未装配（fs 工具插件未携带注册表），无法 run_in_background");
        }
        Process process;
        try {
            process = startProcess(command);
        } catch (IOException e) {
            throw new RuntimeException("bash 无法启动后台进程: " + e.getMessage(), e);
        }
        BackgroundTask task = registry.start(process, command);
        return "[后台任务] " + task.taskId() + " 已启动：" + command
                + "\n（后台不受 timeoutMs 约束；用 task-output 读输出/等待完成，task-stop 终止。"
                + "完成时会收到通知。）";
    }

    @Override public String execute(ToolExecution exec) {
        JsonNode args = exec.args();
        String command = args.path("command").asText("");
        if (command.isBlank()) return error("参数 command 不能为空");

        if (args.path("run_in_background").asBoolean(false)) {
            return executeInBackground(command);
        }
        long timeoutMs = timeoutFor(args);

        Process process;
        try {
            process = startProcess(command);
        } catch (IOException e) {
            throw new RuntimeException("bash 无法启动进程: " + e.getMessage(), e);
        }

        // 双流并发读：管道写满即阻塞子进程，单线程顺序读会与 waitFor 互锁。
        // 输出分层（M23 工单 05）：内存尾窗 + 懒 spill 落盘（超 inline 预算才创建文件）
        int inlineTail = outputConfig.inlineTailChars();
        StreamCapture stdout = new StreamCapture(inlineTail, outputConfig.spillMaxChars(),
                spillPath("stdout"));
        StreamCapture stderr = new StreamCapture(inlineTail, outputConfig.spillMaxChars(),
                spillPath("stderr"));
        Thread stdoutReader = Thread.ofVirtual().start(() -> capture(process.getInputStream(), stdout));
        Thread stderrReader = Thread.ofVirtual().start(() -> capture(process.getErrorStream(), stderr));

        boolean exited;
        try {
            exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminateTree(process);
            throw new RuntimeException("bash 执行被中断", e);
        }
        if (!exited) {
            terminateTree(process);
        }
        boolean stdoutClosed = joinQuietly(stdoutReader);
        boolean stderrClosed = joinQuietly(stderrReader);
        stdout.finish();
        stderr.finish();

        StringBuilder sb = new StringBuilder();
        appendStdout(sb, stdout);
        appendStderr(sb, stderr);
        appendSpillInfo(sb, "stdout", stdout);
        appendSpillInfo(sb, "stderr", stderr);
        sb.append(exited
                ? "[exit code: " + process.exitValue() + "]"
                : "[timed out] " + timeoutMs + "ms limit reached; process tree terminated");
        if (!stdoutClosed || !stderrClosed) {
            // 命令已退但流仍被后台进程持有：已达上限的部分不是全部，明示而非静默截半
            sb.append("\n[output may be incomplete: a background process still holds the stream]");
        }
        return sb.toString();
    }

    /** 超时 clamp：模型可传低值，缺省 120s，上限 600s；非正数/非数值按缺省。 */
    static long timeoutFor(JsonNode args) {
        JsonNode node = args.path("timeoutMs");
        return node.isNumber() && node.asLong() > 0
                ? Math.min(node.asLong(), MAX_TIMEOUT_MS)
                : DEFAULT_TIMEOUT_MS;
    }

    /**
     * 终止进程树：先子孙后本体（SIGTERM 宽限 500ms，仍活着一律 SIGKILL）——后台孙进程
     * 会持有管道存活，只杀 bash 不够；忽略 SIGTERM 的孙进程也不放过（宽限期后按句柄
     * 复查，不为"父进程退没退"所掩盖）。句柄先快照：父进程死亡后
     * {@code descendants()} 即空，届时再取就找不到它们了。
     */
    static void terminateTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        waitQuietly(process, 500);
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
            waitQuietly(process, 2_000);
        }
    }

    /** 有界等待进程退出（中断按未退出处理并保留中断标志——调用方随后按 isAlive 复查）。 */
    static void waitQuietly(Process process, long millis) {
        try {
            process.waitFor(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 有界等待读线程收尾：进程退出后管道即关，正常毫秒级返回；false = 流仍被持有。 */
    private static boolean joinQuietly(Thread thread) {
        try {
            thread.join(2_000);
            return !thread.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 读尽一个流：上限内落 {@link StreamCapture}，其余只计数（缓冲区仍排空，不阻塞子进程）。 */
    static void capture(InputStream in, StreamCapture slot) {
        char[] buffer = new char[8_192];
        try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                slot.accept(buffer, read);
            }
        } catch (IOException ignored) {
            // 进程被终止 / 管道关闭：保留已读部分，marker 语义不变
        }
    }

    /** stdout 段：原样（空流不留痕）。 */
    private static void appendStdout(StringBuilder sb, StreamCapture capture) {
        appendBody(sb, capture.text(), capture.omitted(), "stdout");
    }

    /** stderr 段：非空才带 [stderr] 标题（空流不留痕）。 */
    private static void appendStderr(StringBuilder sb, StreamCapture capture) {
        String text = capture.text();
        if (text.isEmpty()) {
            return;
        }
        sb.append("[stderr]\n");
        appendBody(sb, text, capture.omitted(), "stderr");
    }

    /** 段体：文本 + 缺行尾补换行 + 超帽丢弃 marker（M23 工单 05：被裁历史归 spill，仅超帽丢弃才报省略）。 */
    private static void appendBody(StringBuilder sb, String text, long omitted, String label) {
        sb.append(text);
        if (!text.isEmpty() && !text.endsWith("\n")) {
            sb.append('\n');
        }
        if (omitted > 0) {
            sb.append('[').append(label).append(" spill 超帽: ").append(omitted)
              .append(" chars dropped — 输出过大，请拆分命令或重定向到文件]\n");
        }
    }

    /** spill 回读信息（M23 工单 05）：成功落盘给路径与回读指引；失败/超帽各自显式告警。 */
    private static void appendSpillInfo(StringBuilder sb, String label, StreamCapture capture) {
        if (capture.spillFailed()) {
            sb.append('[').append(label).append(" spill 落盘失败] 共 ").append(capture.trimmedTotal())
              .append(" 字符未保留，回读不可用——请缩小输出或重定向到文件]\n");
            return;
        }
        if (!capture.hasSpill()) {
            return;
        }
        sb.append('[').append(label).append(" spilled] 前 ").append(capture.spilledChars())
          .append(" 字符已落盘，read 此文件回读全文: ").append(capture.spillPath()).append('\n');
        if (capture.spillFull()) {
            sb.append('[').append(label).append(" spill 超帽] 达上限停止写入，其后 ")
              .append(capture.droppedAfterFullCount()).append(" 字符未保留——请拆分命令或重定向到文件\n");
        }
    }

    /** spill 文件路径：Duo home 临时区 bash-spill 子目录（进程退出由插件清理路径删除）。 */
    private static java.nio.file.Path spillPath(String stream) {
        return dev.duo.harness.core.api.boot.DuoHome.resolve().resolveDir("tmp/bash-spill")
                .resolve("bash-" + SPILL_SEQ.incrementAndGet() + "-" + stream + ".txt");
    }

    /**
     * 单流读取槽（M23 工单 05 升级为分层）：读线程边读边落——内存保最近
     * {@code keepChars} 尾窗，被裁的历史懒写入 spill 文件（超 {@code spillMaxChars}
     * 停写并计数丢弃——告警不静默）；主线程有界等待后取用，读未收尾（子进程留下
     * 持有管道的后台孙进程）也返回已读部分。spill 文件由插件停止时的清理路径删除。
     */
    static final class StreamCapture {

        private final int keepChars;
        private final long spillMaxChars;
        private final java.nio.file.Path spillPath;
        private final StringBuilder kept = new StringBuilder();
        private long trimmedTotal;          // 已裁入 spill 的历史字符数
        private long spilledChars;          // 实际写入 spill 的字符数
        private long droppedAfterFull;      // 超帽/落盘失败后丢弃的字符数
        private boolean spillFull;          // 超帽或落盘失败：停写并告警（不静默）
        private boolean spillFailed;        // true = 落盘 IO 失败（区别于正常超帽）
        private boolean closed;             // finish 后停写（迟到 accept 不再碰 writer）
        private java.io.Writer spillWriter;

        StreamCapture(int keepChars, long spillMaxChars, java.nio.file.Path spillPath) {
            this.keepChars = Math.max(keepChars, 1);
            this.spillMaxChars = Math.max(spillMaxChars, 1);
            this.spillPath = spillPath;
        }

        /** 无 spill 的兼容形态（纯内存尾窗，如治理层旁路的小输出场景）。 */
        StreamCapture(int keepChars) {
            this(keepChars, Long.MAX_VALUE, null);
        }

        synchronized void accept(char[] buffer, int length) {
            kept.append(buffer, 0, length);
            // 尾窗裁头：被裁历史写 spill（懒开）；超帽/落盘失败后停写只计丢弃
            if (kept.length() > keepChars) {
                int overflow = kept.length() - keepChars;
                String head = kept.substring(0, overflow);
                kept.delete(0, overflow);
                trimmedTotal += overflow;
                if (closed || spillFull) {
                    droppedAfterFull += overflow;
                    return;
                }
                try {
                    writeSpill(head);
                } catch (java.io.IOException e) {
                    spillFailed = true; // 落盘失败独立标记（文案区分于超帽）
                    spillFull = true;   // 停写同语义收敛，告警不静默
                    droppedAfterFull += overflow;
                }
            }
        }

        private void writeSpill(String text) throws java.io.IOException {
            if (spilledChars + text.length() > spillMaxChars) {
                spillFull = true;
                closeSpill();
                droppedAfterFull += text.length();
                return;
            }
            java.nio.file.Files.createDirectories(spillPath.getParent());
            spillWriter = java.nio.file.Files.newBufferedWriter(spillPath,
                    StandardCharsets.UTF_8);
            spillWriter.write(text);
            spilledChars += text.length();
            if (spilledChars >= spillMaxChars) {
                spillFull = true;
                closeSpill();
            }
        }

        private synchronized void closeSpill() {
            if (spillWriter != null) {
                try {
                    spillWriter.close();
                } catch (java.io.IOException ignored) {
                    // 关闭失败不影响状态标记
                }
                spillWriter = null;
            }
        }

        /** 收尾：流结束后调用——关闭 writer 并停写（迟到 accept 只计丢弃，不再碰 writer）。 */
        synchronized void finish() {
            closed = true;
            closeSpill();
        }

        synchronized String text() {
            return kept.toString();
        }

        synchronized long omitted() {
            return droppedAfterFull; // 超帽丢弃量（原 100k 截断计数语义由 spill 语义取代）
        }

        /** spill 是否有内容可回读（成功落盘且被裁历史 > 0；失败态走失败文案）。 */
        synchronized boolean hasSpill() {
            return trimmedTotal > 0 && !spillFailed && spillPath != null;
        }

        /** 落盘是否失败（区别于超帽——文案点名「回读不可用」）。 */
        synchronized boolean spillFailed() {
            return spillFailed;
        }

        synchronized boolean spillFull() {
            return spillFull;
        }

        synchronized java.nio.file.Path spillPath() {
            return spillPath;
        }

        synchronized long trimmedTotal() {
            return trimmedTotal;
        }

        synchronized long spilledChars() {
            return spilledChars;
        }

        synchronized long droppedAfterFullCount() {
            return droppedAfterFull;
        }
    }

    private static String error(String msg) { return "[bash 错误] " + msg; }
}