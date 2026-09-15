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
 * 清理防孤儿。每流输出有界（{@link #MAX_STREAM_CHARS} 是内存护栏，取值高于治理层
 * spill 阈值）：正常的大输出由治理层落盘给定位符、可回读全文，超护栏的部分只计数
 * 不保留、marker 报省略量与补救方向。命令退出而流仍被后台进程持有（后台任务未收尾）
 * 时输出可能不完整，以 marker 明示而非静默截半。非零退出**不是错误**：
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
    /**
     * 单流保留上限（字符）：内存护栏，高于治理层 spill 阈值（50000）——超阈值的大输出
     * 由治理层卸载落盘（全文可回读），此处只挡病态体量。超限部分只计数不保留，管道仍
     * 持续排空（子进程不因写满阻塞）。
     */
    private static final int MAX_STREAM_CHARS = 100_000;

    private final WorkspacePolicy workspace;

    public FsBashTool(WorkspacePolicy workspace) {
        this.workspace = workspace;
    }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "执行 shell 命令（bash -c，每次调用全新进程，工作目录为 workspace 根）。"
                + "返回输出与退出码——非零退出不算错误。timeoutMs 指定超时（默认 120s，上限 600s）。";
    }
    @Override public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"type\":\"object\",\"properties\":{"
                + "\"command\":{\"type\":\"string\",\"description\":\"要执行的命令（bash -c 语义）\"},"
                + "\"timeoutMs\":{\"type\":\"number\",\"description\":\"超时毫秒（默认 120000，上限 600000）\"}"
                + "},\"required\":[\"command\"]}");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Override public boolean requiresApproval() { return true; }

    @Override public String execute(ToolExecution exec) {
        JsonNode args = exec.args();
        String command = args.path("command").asText("");
        if (command.isBlank()) return error("参数 command 不能为空");
        long timeoutMs = timeoutFor(args);

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder("bash", "-c", command);
            builder.directory(workspace.root().toFile());
            Map<String, String> env = builder.environment();
            env.put("NO_COLOR", "1");
            env.put("TERM", "dumb");
            env.put("PAGER", "cat");
            // stdin 接空设备：管道无人写即挂起到超时，继承父进程 stdin 则会偷吃 REPL 输入
            builder.redirectInput(new java.io.File("/dev/null"));
            process = builder.start();
        } catch (IOException e) {
            throw new RuntimeException("bash 无法启动进程: " + e.getMessage(), e);
        }

        // 双流并发读：管道写满即阻塞子进程，单线程顺序读会与 waitFor 互锁
        StreamCapture stdout = new StreamCapture();
        StreamCapture stderr = new StreamCapture();
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

        StringBuilder sb = new StringBuilder();
        appendStdout(sb, stdout);
        appendStderr(sb, stderr);
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
    private static void terminateTree(Process process) {
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
    private static void waitQuietly(Process process, long millis) {
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
    private static void capture(InputStream in, StreamCapture slot) {
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

    /** 段体：文本 + 缺行尾补换行 + 截断 marker（文本与省略量取自同一次快照）。 */
    private static void appendBody(StringBuilder sb, String text, long omitted, String label) {
        sb.append(text);
        if (!text.isEmpty() && !text.endsWith("\n")) {
            sb.append('\n');
        }
        if (omitted > 0) {
            sb.append('[').append(label).append(" truncated: ").append(omitted)
              .append(" chars omitted — narrow the command or redirect the rest to a file]\n");
        }
    }

    /**
     * 单流读取槽：读线程边读边落，主线程有界等待后取用——读未收尾（子进程留下了
     * 持有管道的后台孙进程）也返回已读部分，不空手。
     */
    private static final class StreamCapture {

        private final StringBuilder kept = new StringBuilder();
        private long omitted;

        synchronized void accept(char[] buffer, int length) {
            int room = MAX_STREAM_CHARS - kept.length();
            if (room > 0) {
                kept.append(buffer, 0, Math.min(length, room));
            }
            omitted += Math.max(0, length - Math.max(room, 0));
        }

        synchronized String text() {
            return kept.toString();
        }

        synchronized long omitted() {
            return omitted;
        }
    }

    private static String error(String msg) { return "[bash 错误] " + msg; }
}