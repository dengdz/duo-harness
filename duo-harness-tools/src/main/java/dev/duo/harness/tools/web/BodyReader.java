package dev.duo.harness.tools.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 响应体限读器（M20）：单一整体 deadline 覆盖连接后读 body 全程（DSH 同语义）——
 * JDK HttpClient 的请求超时只保到响应头，body 阶段的挂死由本类的看门狗兜底
 * （到点强制关闭流，阻塞中的 read 以异常解除）。
 *
 * <p>字节上限内流式读取：超限即停（截断语义由调用方按"恰好填满不算截断"裁定，
 * ADR-0021 决策 6）；不做无上限缓冲——Content-Length 说谎的服务器不能灌爆内存。</p>
 *
 * <p>线程约定：看门狗为进程级单守护线程，只做"到点关流"一件事；read 在调用方
 * 工具线程执行。</p>
 */
final class BodyReader {

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "web-fetch-body-watchdog");
        t.setDaemon(true);
        return t;
    });

    private BodyReader() { }

    /**
     * 上限内读取全部字节。
     *
     * @param maxBytes    字节上限（读满即停并置位截断标志，多出的字节不读）
     * @param deadline    整体读时限；到点看门狗强制关流，阻塞中的 read 抛 {@link FetchTimeoutException}
     * @param truncatedOut 单元素出口：是否因超上限而截断
     */
    static byte[] read(InputStream in, long maxBytes, Duration deadline, boolean[] truncatedOut) throws IOException {
        AtomicBoolean killed = new AtomicBoolean(false);
        ScheduledFuture<?> kill = WATCHDOG.schedule(() -> {
            killed.set(true);
            try {
                in.close();
            } catch (IOException ignored) {
                // 看门狗关流失败无可补救：读侧稍后自会感知
            }
        }, deadline.toMillis(), TimeUnit.MILLISECONDS);
        try (in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            boolean truncated = false;
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (total + n > maxBytes) {
                    buf.write(chunk, 0, (int) (maxBytes - total));
                    truncated = true;
                    break;
                }
                buf.write(chunk, 0, n);
                total += n;
            }
            truncatedOut[0] = truncated;
            return buf.toByteArray();
        } catch (IOException e) {
            if (killed.get()) {
                throw new FetchTimeoutException();
            }
            throw e;
        } finally {
            kill.cancel(false);
        }
    }

    /** 读 body 超过整体 deadline（看门狗关流所致）——消息按工具错误口径点名时长。 */
    static final class FetchTimeoutException extends RuntimeException {

        FetchTimeoutException() {
            super("超时——目标响应过慢或挂起");
        }
    }
}
