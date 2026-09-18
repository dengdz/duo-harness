package dev.duo.harness.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.core.api.boot.DuoHome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 钩子进程执行器（ADR-0019 决策 3）：stdin 喂 JSON 载荷；{@code args} 在场 =
 * 与 command 一起 exec 直启（不经 shell），缺省 {@code sh -c} shell 形态；
 * 环境注入 {@code DUO_HOME}；限时等待，超时 {@code destroyForcibly}（输出丢弃，
 * 放行与否由调用方按 fail-open 裁量）。stdout/stderr 各由独立虚拟线程收集——
 * 防管道写满导致的互相等待死锁。
 */
final class HookRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 输出收集承载：共享虚拟线程执行器（长生命周期，无核心线程成本）。 */
    private static final ExecutorService STREAM_READERS = Executors.newVirtualThreadPerTaskExecutor();

    private HookRunner() {
    }

    /**
     * 一次钩子执行的结局：{@code produced()} 为 true 表示进程正常退出并拿到了
     * 退出码与输出（裁定由调用方按 exit code / 输出解读）；否则为超时或启动失败
     * （fail-open 放行面）。
     */
    record Outcome(int exitCode, String stdout, String stderr, boolean timedOut,
                   String startFailure) {

        static Outcome failedToStart(String message) {
            return new Outcome(-1, "", "", false, message);
        }

        static Outcome ofTimedOut() {
            return new Outcome(-1, "", "", true, null);
        }

        /** 是否产生了可解读的裁定（正常退出）。 */
        boolean produced() {
            return !timedOut && startFailure == null;
        }

        /** 诊断描述（WARN 日志点名用）。 */
        String diagnosis(String command) {
            if (startFailure != null) {
                return command + " → " + startFailure;
            }
            if (timedOut) {
                return command + " → 执行超时被终止（输出已丢弃）";
            }
            return command + " → exit=" + exitCode;
        }
    }

    /** 执行钩子：阻塞至退出、超时或启动失败。 */
    static Outcome run(HookHandler handler, JsonNode payload) {
        List<String> cmdline = cmdline(handler);
        ProcessBuilder builder = new ProcessBuilder(cmdline);
        builder.environment().put(DuoHome.ENV_OVERRIDE, DuoHome.resolve().root().toString());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return Outcome.failedToStart("进程启动失败: " + rootMessage(e));
        }
        // stdin 与 stdout/stderr 同为后台收集：喂入不占主等待线程——不读 stdin 的钩子
        // 遇到大载荷时写满管道的阻塞不蚕食超时预算，waitFor 覆盖进程全生命周期
        STREAM_READERS.submit(() -> feedStdin(process, payload));
        Future<String> stdout = STREAM_READERS.submit(
                () -> readAll(process.getInputStream()));
        Future<String> stderr = STREAM_READERS.submit(
                () -> readAll(process.getErrorStream()));
        boolean finished;
        try {
            finished = process.waitFor(handler.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return Outcome.failedToStart("钩子执行被中断");
        }
        if (!finished) {
            process.destroyForcibly();
            return Outcome.ofTimedOut();
        }
        try {
            return new Outcome(process.exitValue(),
                    stdout.get(2, TimeUnit.SECONDS),
                    stderr.get(2, TimeUnit.SECONDS),
                    false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.failedToStart("钩子输出收集被中断");
        } catch (ExecutionException | TimeoutException e) {
            // 进程已退出而管道读取异常：保守按放行面处理（fail-open）
            return Outcome.failedToStart("钩子输出读取失败: " + rootMessage(e));
        }
    }

    /** exec 直启（args 在场）或 sh -c shell 形态（缺省）。 */
    private static List<String> cmdline(HookHandler handler) {
        if (handler.args().isEmpty()) {
            return List.of("/bin/sh", "-c", handler.command());
        }
        List<String> exec = new ArrayList<>();
        exec.add(handler.command());
        exec.addAll(handler.args());
        return List.copyOf(exec);
    }

    /** stdin 喂 JSON 载荷并关闭输出流；进程提前退出时的写失败不影响退出码收取。 */
    private static void feedStdin(Process process, JsonNode payload) {
        try {
            process.getOutputStream().write(MAPPER.writeValueAsBytes(payload));
            process.getOutputStream().flush();
        } catch (IOException ignored) {
            // 进程先于读入退出（如 false 命令）：退出码照常收取
        } finally {
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // 已关
            }
        }
    }

    private static String readAll(java.io.InputStream stream) throws IOException {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String rootMessage(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
