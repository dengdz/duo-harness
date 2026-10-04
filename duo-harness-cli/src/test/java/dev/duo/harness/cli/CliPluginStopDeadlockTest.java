package dev.duo.harness.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * M37 工单 02 首日实测回归锁：读者线程阻塞在 readLine() 时 stop() 必须立即可完成——
 * 修复前 stop() 调 in.close()，与阻塞读者同锁（JDK InternalLock）互等=死锁（桌面壳
 * SIGTERM 实测 40s+ 挂死）。修复后 stop 不碰 reader，收尾交给 JVM halt。
 */
class CliPluginStopDeadlockTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：CliPluginStopDeadlockTest —— 读者阻塞在 readLine 时 stop 不死锁（M37-02） ===");
    }

    @Test
    void stopCompletesWhileReaderBlockedInReadLine() throws Exception {
        PipedInputStream pipeIn = new PipedInputStream();
        PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
        BufferedReader in = new BufferedReader(new InputStreamReader(pipeIn));
        CliPlugin plugin = new CliPlugin(in,
                new PrintStream(OutputStream.nullOutputStream()), null, null);
        Thread reader = new Thread(() -> {
            try {
                in.readLine(); // 管道无数据且写端不关：阻塞在此并持有 reader 内部锁
            } catch (IOException ignored) {
                // 进程收尾形态，忽略
            }
        });
        reader.start();
        Thread.sleep(300); // 等读者线程真正进入 readLine（锁被持有）
        try {
            Thread stopper = new Thread(plugin::stop);
            stopper.start();
            stopper.join(3000);
            assertFalse(stopper.isAlive(), "stop() 须立即可完成（修复前：in.close() 与阻塞读者同锁互等死锁）");
        } finally {
            pipeOut.close(); // 收尾：放读者线程 EOF 退出
        }
    }
}
