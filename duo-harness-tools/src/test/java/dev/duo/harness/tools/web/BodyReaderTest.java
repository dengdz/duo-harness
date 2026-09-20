package dev.duo.harness.tools.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 响应体限读器单测：字节上限截断语义（恰好填满不算截断）与看门狗 deadline。
 * 纯内存流 + 阻塞流，零网络。
 */
class BodyReaderTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：BodyReaderTest —— 限读器（截断语义/看门狗兜底） ===");
    }

    @Test
    void 上限内完整读取() throws Exception {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        boolean[] truncated = {true};
        byte[] out = BodyReader.read(new ByteArrayInputStream(data), 100, Duration.ofSeconds(5), truncated);
        assertArrayEquals(data, out);
        assertFalse(truncated[0]);
    }

    @Test
    void 超上限截断只留前缀() throws Exception {
        byte[] data = "0123456789".getBytes(StandardCharsets.UTF_8);
        boolean[] truncated = {false};
        byte[] out = BodyReader.read(new ByteArrayInputStream(data), 4, Duration.ofSeconds(5), truncated);
        assertEqualsBytes("0123", out);
        assertTrue(truncated[0], "流里还有更多字节应置截断");
    }

    @Test
    void 恰好填满不算截断() throws Exception {
        byte[] data = "0123".getBytes(StandardCharsets.UTF_8);
        boolean[] truncated = {true};
        byte[] out = BodyReader.read(new ByteArrayInputStream(data), 4, Duration.ofSeconds(5), truncated);
        assertArrayEquals(data, out);
        assertFalse(truncated[0], "恰好填满不算截断（ADR-0021 决策 6）");
    }

    @Test
    void 读挂死由看门狗关流解除() {
        // 拟真 socket 语义：close() 中断阻塞中的 read（真连接关流时 read 以异常解除）
        InputStream neverEnding = new InputStream() {
            private Thread reader;

            @Override
            public int read() throws IOException {
                reader = Thread.currentThread();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("stream closed");
                }
                return -1;
            }

            @Override
            public void close() {
                if (reader != null) {
                    reader.interrupt();
                }
            }
        };
        long t0 = System.currentTimeMillis();
        assertThrows(BodyReader.FetchTimeoutException.class,
                () -> BodyReader.read(neverEnding, 100, Duration.ofMillis(200), new boolean[1]));
        assertTrue(System.currentTimeMillis() - t0 < 5_000, "看门狗应在 deadline 附近解除阻塞");
    }

    private static void assertEqualsBytes(String expected, byte[] actual) {
        assertTrue(new String(actual, StandardCharsets.UTF_8).equals(expected),
                "字节内容应为 " + expected + "，实际 " + new String(actual, StandardCharsets.UTF_8));
    }
}
