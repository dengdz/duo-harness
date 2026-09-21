package dev.duo.harness.tools.fs;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台任务注册表用例（M23 工单 04，ADR-0025 决策二）：start 接管进程与完成监视线程、
 * 通知 first-wins（每任务至多一条）、get/all 索引、task-stop 杀树幂等、shutdownAll
 * 全杀（插件树停止路径）。
 */
class BackgroundTaskRegistryTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：BackgroundTaskRegistryTest —— 后台任务注册表：start/通知 first-wins、"
                + "stop 杀树幂等、shutdownAll 全杀（5 用例） ===");
    }

    private static Process startSleep(long seconds) throws IOException {
        return new ProcessBuilder("bash", "-c", "sleep " + seconds).start();
    }

    @Test
    @Timeout(15)
    void startNotifiesOnCompletionExactlyOnce() throws Exception {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        List<BackgroundTask> notices = new CopyOnWriteArrayList<>();
        registry.addListener(notices::add);

        BackgroundTask task = registry.start(startSleep(0), "echo quick");
        // 等 settle + 通知
        long deadline = System.currentTimeMillis() + 5_000;
        while (notices.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, notices.size(), "完成通知到达（first-wins 一条）");
        assertEquals(task.taskId(), notices.get(0).taskId());
        assertTrue(notices.get(0).notice().contains("[exit code: 0]"), "终态描述含退出码");
        Thread.sleep(300);
        assertEquals(1, notices.size(), "通知不重复（first-wins）");
        assertEquals(BackgroundTask.State.EXITED, task.state());
        assertTrue(task.isCompleted());
    }

    @Test
    @Timeout(15)
    void getAndAllIndexTasksById() throws Exception {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        BackgroundTask one = registry.start(startSleep(5), "sleep 5");
        assertTrue(registry.get(one.taskId()).isPresent(), "taskId 索引命中");
        assertTrue(one.taskId().startsWith("bg-"), "taskId 形态 bg-N");
        assertTrue(registry.get("bg-nope").isEmpty(), "未知 id 空");
        assertEquals(1, registry.all().size());
        one.terminate();
    }

    @Test
    @Timeout(15)
    void stopKillsProcessTreeAndIsIdempotent() throws Exception {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        BackgroundTask task = registry.start(startSleep(60), "sleep 60");
        task.terminate();
        long deadline = System.currentTimeMillis() + 5_000;
        while (task.process().isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(task.process().isAlive(), "杀树后进程退出");
        assertEquals(BackgroundTask.State.KILLED, task.state());
        task.terminate(); // 幂等：已结束原样返回不抛
        assertEquals(BackgroundTask.State.KILLED, task.state());
    }

    @Test
    @Timeout(15)
    void shutdownAllKillsEveryRunningTask() throws Exception {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        List<BackgroundTask> notices = new CopyOnWriteArrayList<>();
        registry.addListener(notices::add);
        BackgroundTask a = registry.start(startSleep(60), "sleep 60a");
        BackgroundTask b = registry.start(startSleep(60), "sleep 60b");
        registry.shutdownAll();
        long deadline = System.currentTimeMillis() + 5_000;
        while ((a.process().isAlive() || b.process().isAlive())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(a.process().isAlive() || b.process().isAlive(), "全杀（插件树停止路径）");
        // 关闭杀的完成监视线程退出后状态已被 terminate 置 KILLED——通知是否到达不强断言
        // （shutdown 路径通知语义由监视线程的返回路径决定，可见化不依赖它）
        assertTrue(notices.size() <= 2);
    }

    @Test
    @Timeout(15)
    void notificationDeliveredToListenersRegisteredBeforeSettle() throws Exception {
        // 迟到监听器收不到既有终态（javadoc 明示）——本用例锁定"先注册后 start"的主路径
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        CountDownLatch noticed = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        registry.addListener(t -> {
            count.incrementAndGet();
            noticed.countDown();
        });
        registry.start(new ProcessBuilder("bash", "-c", "true").start(), "true");
        assertTrue(noticed.await(5, TimeUnit.SECONDS), "settle 后通知到达");
        assertEquals(1, count.get());
    }
}
