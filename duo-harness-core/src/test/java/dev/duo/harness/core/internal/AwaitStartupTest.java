package dev.duo.harness.core.internal;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.core.api.PluginHandle;
import dev.duo.harness.core.api.PluginState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * awaitStartup 可配超时（ADR-0019）用例（接缝 B，编程 API）：超时点名缺失服务、
 * 超时后插件保持 PENDING 且服务就绪仍可激活、零时长即立即探测、负时长拒绝。
 * 等待点名日志的静态口径（waitAnnouncement）在本包直测；日志行本身由
 * announceWaitIfPending 打出（slf4j 输出不进断言）。缺依赖的静默卡死由
 * "超时 + 点名"双向了结。
 */
class AwaitStartupTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：AwaitStartupTest —— awaitStartup 可配超时：超时点名缺失、"
                + "超时后可激活、零时长探测、负时长拒绝、等待点名口径（5 用例） ===");
    }

    /** 硬依赖永不就绪的等待者。 */
    public static class WaitingPlugin implements Plugin<Void> {

        @Override
        public Set<String> inject() {
            return Set.of("late-service");
        }

        @Override
        public Class<Void> configType() {
            return null;
        }

        @Override
        public Disposable apply(Context ctx, Void config) {
            return null;
        }
    }

    @Test
    void timeoutNamesMissingServicesAndKeepsPending() {
        Context root = Context.root();
        try {
            PluginHandle handle = root.plugin(new WaitingPlugin(), null);
            PluginException e = assertThrows(PluginException.class,
                    () -> handle.awaitStartup(Duration.ofMillis(120)));
            assertTrue(e.getMessage().contains("超时"), e.getMessage());
            assertTrue(e.getMessage().contains("late-service"), "点名缺失服务: " + e.getMessage());
            assertEquals(PluginState.PENDING, handle.state(), "超时后插件保持 PENDING");
        } finally {
            root.dispose();
        }
    }

    @Test
    void afterTimeoutServiceArrivalStillActivates() throws Exception {
        // 超时不撤销等待语义：插件原地 PENDING，服务出现照常激活，再次等待即成功
        Context root = Context.root();
        try {
            PluginHandle handle = root.plugin(new WaitingPlugin(), null);
            assertThrows(PluginException.class, () -> handle.awaitStartup(Duration.ofMillis(80)));
            root.provide("late-service", new Object());
            handle.awaitStartup(Duration.ofSeconds(2));
            assertEquals(PluginState.ACTIVE, handle.state(), "超时后服务就绪照常激活");
        } finally {
            root.dispose();
        }
    }

    @Test
    void zeroDurationActsAsImmediateProbe() {
        Context root = Context.root();
        try {
            PluginHandle handle = root.plugin(new WaitingPlugin(), null);
            PluginException e = assertThrows(PluginException.class,
                    () -> handle.awaitStartup(Duration.ZERO));
            assertTrue(e.getMessage().contains("late-service"), e.getMessage());
            assertEquals(PluginState.PENDING, handle.state());
        } finally {
            root.dispose();
        }
    }

    @Test
    void negativeDurationRejected() {
        Context root = Context.root();
        try {
            PluginHandle handle = root.plugin(new WaitingPlugin(), null);
            assertThrows(IllegalArgumentException.class,
                    () -> handle.awaitStartup(Duration.ofMillis(-1)));
        } finally {
            root.dispose();
        }
    }

    @Test
    void waitAnnouncementNamesMissingServices() {
        // 本包直测：PENDING 且缺依赖 → 点名等待对象；直接构造实例（不经 root，避免无限等待）
        PluginInstance instance = new PluginInstance(
                new PluginRegistry(), new EventsImpl(), new ServiceRegistry(),
                new WaitingPlugin(), null, "测试等待插件", Set.of("late-service"), Set.of());
        String announcement = instance.waitAnnouncement();
        assertTrue(announcement != null && announcement.contains("late-service")
                        && announcement.contains("缺失服务"),
                "PENDING 且缺依赖时应点名等待对象: " + announcement);
    }
}
