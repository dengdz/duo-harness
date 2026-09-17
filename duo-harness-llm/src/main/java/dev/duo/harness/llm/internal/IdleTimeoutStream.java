package dev.duo.harness.llm.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 流式 body 的空闲超时包装（M16 工单 05）：连续 idleTimeoutMs 没有从 provider
 * 收到新字节即抛 {@link StreamIdleTimeoutException}——provider 半开连接（发完
 * 响应头后断流不关）不再永久阻塞读取循环。
 *
 * <p>实现为<b>虚拟线程泵 + 有界队列</b>：泵线程阻塞读 provider 字节并推入队列
 * （虚拟线程阻塞不占平台线程，ADR-0002 同源），消费方带超时地从队列取数——
 * 阻塞读无法直接限时，泵+队列是可移植的超时形态。不用 available() 轮询：
 * JDK 响应流的 available() 不保证反映缓冲中的数据（实测恒 0 会导致永久误判超时）。</p>
 *
 * <p>超时后由消费方关闭本流（try-with-resources 随异常传播关闭）：泵线程阻塞中
 * 的 read 随流关闭抛出退出，socket fd 释放。泵线程按字节推进更新
 * {@code lastProviderByteAt}——空闲判定以 provider 侧最后活动为准，消费方慢
 * 不误判。</p>
 */
final class IdleTimeoutStream extends InputStream {

    /** 空闲检查轮询间隔毫秒（触发延迟与空转成本的折中）。 */
    private static final long POLL_INTERVAL_MS = 50;

    private final InputStream in;
    /** 泵读缓冲字节数。 */
    private static final int PUMP_BUFFER = 8192;

    private final BlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(64);
    private final Thread pump;
    private final long idleTimeoutNanos;
    private volatile long lastProviderByteAt;
    private volatile boolean eof;
    private volatile IOException pumpError;
    private volatile boolean closed;

    private byte[] current;
    private int position;

    /** @param in 被包装的响应体流 @param idleTimeoutMs 空闲超时毫秒（须为正） */
    IdleTimeoutStream(InputStream in, long idleTimeoutMs) {
        this.in = in;
        if (idleTimeoutMs < 1) {
            throw new IllegalArgumentException("idleTimeoutMs 至少为 1: " + idleTimeoutMs);
        }
        this.idleTimeoutNanos = idleTimeoutMs * 1_000_000L;
        this.lastProviderByteAt = System.nanoTime();
        this.pump = Thread.ofVirtual().name("llm-idle-pump").start(() -> pump(in));
    }

    private void pump(InputStream in) {
        try {
            byte[] buffer = new byte[PUMP_BUFFER];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (n > 0) {
                    lastProviderByteAt = System.nanoTime();
                    chunks.put(Arrays.copyOf(buffer, n));
                }
            }
            eof = true;
        } catch (InterruptedException e) {
            // 泵等待被中断：错误在消费侧可见
            pumpError = new IOException("泵读取被中断", e);
            eof = true;
        } catch (IOException e) {
            // 流被关闭（超时后消费方 close）或读取失败：泵退出，错误在消费侧可见
            pumpError = e;
            eof = true;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        long deadline = lastProviderByteAt + idleTimeoutNanos;
        while (true) {
            byte[] block;
            try {
                block = chunks.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("流式读取被中断", e);
            }
            if (block != null) {
                int n = Math.min(len, block.length - position);
                System.arraycopy(block, position, b, off, n);
                position += n;
                if (position == block.length) {
                    current = null;
                    position = 0;
                } else {
                    current = block;
                }
                return n;
            }
            if (pumpError != null) {
                throw pumpError;
            }
            if (eof) {
                return -1;
            }
            if (closed) {
                throw new IOException("流已关闭");
            }
            if (System.nanoTime() >= deadline) {
                throw new StreamIdleTimeoutException(
                        "流式空闲超时: " + (idleTimeoutNanos / 1_000_000) + "ms 内无新字节");
            }
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? n : (one[0] & 0xFF);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        in.close();
    }
}
