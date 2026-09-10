package dev.duo.harness.mcp.internal;

import dev.duo.harness.core.api.PluginException;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单个 MCP 服务器的连接生命周期：首连 → 断连 → 指数退避重连 → 稳定窗口清零 → 预算耗尽放弃。
 *
 * <p>运行模型（ADR-0002）：首连在调用方线程同步执行（承载 failOnStartupError 语义），
 * 后续重连在专用虚拟线程上循环；断连检测依赖 {@code StdioClientTransport.awaitForExit()}
 * （server 进程退出即返回）。dispose 或预算耗尽后循环退出，状态定格。</p>
 */
final class ConnectionSupervisor {

    /** 连接生命周期状态（诊断与测试可观测）。 */
    enum State { CONNECTING, CONNECTED, BACKOFF, GAVE_UP, STOPPED }

    private static final Logger log = LoggerFactory.getLogger(ConnectionSupervisor.class);

    /** 已归一化的连接配置（serverName/command/重连参数等）。 */
    private final McpConnectionOptions options;
    private final ReconnectPolicy policy;
    /** 连接事件监听（工具同步挂钩）。 */
    private final Listener listener;
    /** 状态锁。不用 synchronized：与内核锁惯例一致，虚拟线程不 pin（ADR-0002）。 */
    private final ReentrantLock lock = new ReentrantLock();
    /** 首连完成信号（成功或失败都放行等待者）。 */
    private final CountDownLatch firstAttempt = new CountDownLatch(1);
    /** 生命周期状态；仅在锁内读写。 */
    private State state = State.CONNECTING;
    /** 连续失败计数（稳定窗口清零语义见 {@link ReconnectPolicy#failuresAfterDrop}）。 */
    private int failures;
    /** 最近一次连接成功的起点（稳定窗口计算用）。 */
    private long connectedAt;
    /** 首连失败原因（failOnStartupError 时由 apply 上抛）；非首连失败不记。 */
    private volatile Exception firstFailure;
    /** 停止标志：dispose 置位后循环退出。 */
    private volatile boolean closed;
    /** 当前连接的客户端；断连后置 null。volatile：supervisor 线程与 dispose 交叉。 */
    private volatile McpSyncClient client;
    /** 当前传输（持有 server 进程；awaitForExit 断连检测用）。 */
    private volatile StdioClientTransport transport;
    /** 重连循环线程（dispose 时中断退避睡眠）。 */
    private volatile Thread loopThread;

    /** 连接事件监听：CONNECTED 后触发工具同步；预算耗尽/禁用重连后触发工具注销。 */
    interface Listener {
        default void onConnected(McpSyncClient client) { }

        /**
         * 远端 tools/list_changed 通知：载荷是 SDK 已按通知拉取好的全量工具清单
         * （SDK 的通知处理器内部已完成 listTools 请求，此处直接消费）。
         */
        default void onToolsChanged(List<McpSchema.Tool> tools) { }

        default void onGaveUp() { }
    }

    ConnectionSupervisor(McpConnectionOptions options) {
        this(options, new Listener() { });
    }

    ConnectionSupervisor(McpConnectionOptions options, Listener listener) {
        this.options = options;
        this.listener = listener;
        this.policy = new ReconnectPolicy(
                options.stableWindowMs(), options.reconnectInitialMs(),
                options.reconnectMaxMs(), options.reconnectMaxAttempts());
    }

    /**
     * 阻塞执行首连（调用方线程，直至首连成功或失败落定）。
     * 返回时重连循环已在后台虚拟线程上运行（除非首连即失败且策略要求失败）。
     */
    void runFirstAttempt() {
        loopThread = Thread.ofVirtual().name("mcp-conn-" + options.serverName()).start(this::runLoop);
        try {
            firstAttempt.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待首连被中断", e);
        }
        Exception error = firstFailure;
        if (error != null && options.failOnStartupError()) {
            throw new PluginException(
                    "MCP 服务器 [" + options.serverName() + "] 首连失败（failOnStartupError=true）", error);
        }
    }

    private void runLoop() {
        while (!closed) {
            String server = options.serverName();
            lock.lock();
            try {
                state = State.CONNECTING;
            } finally {
                lock.unlock();
            }
            try {
                connectOnce();
                long startedAt = System.currentTimeMillis();
                boolean stopRequested;
                lock.lock();
                try {
                    // stop() 竞态：连接虽成，但插件已要求停止——不置 CONNECTED，关闭新连接
                    stopRequested = closed;
                    if (!stopRequested) {
                        failures = 0;
                        connectedAt = startedAt;
                        state = State.CONNECTED;
                    }
                } finally {
                    lock.unlock();
                }
                if (stopRequested) {
                    closeClientQuietly();
                    firstAttempt.countDown();
                    return;
                }
                log.info("MCP 服务器 [{}] 已连接", server);

                // 工具同步属于"连接就绪"的一部分：完成后才放行 awaitStartup
                // （同步抛错即连接尝试失败：走下方 catch，由重连预算裁决）
                listener.onConnected(client);
                firstAttempt.countDown();

                // 稳定期：阻塞等待 server 进程退出
                StdioClientTransport active = transport;
                if (active != null) {
                    active.awaitForExit();
                }
                if (closed) {
                    return;
                }
                long uptime = System.currentTimeMillis() - connectedAt;
                boolean giveUpOnDrop;
                lock.lock();
                try {
                    failures = policy.failuresAfterDrop(uptime, failures);
                    giveUpOnDrop = policy.budgetExhausted(failures) || !options.reconnectEnabled();
                    if (giveUpOnDrop) {
                        state = State.GAVE_UP;
                        log.warn("MCP 服务器 [{}] 断连后{}，停止重连", server,
                                options.reconnectEnabled() ? "重连预算耗尽（" + failures + " 次）" : "重连已禁用");
                    } else {
                        state = State.BACKOFF;
                    }
                } finally {
                    lock.unlock();
                }
                closeClientQuietly();
                if (giveUpOnDrop) {
                    listener.onGaveUp();
                    return;
                }
                log.warn("MCP 服务器 [{}] 断连（存活 {}ms），{}ms 后进行第 {} 次重连",
                        server, uptime, policy.backoffDelayMs(failures), failures);
            } catch (Exception e) {
                closeClientQuietly();
                if (closed) {
                    // 停止请求自身打断了 awaitForExit：不是连接故障，别覆盖 STOPPED 也别记失败
                    firstAttempt.countDown();
                    return;
                }
                boolean firstAttemptFailed;
                boolean giveUp;
                lock.lock();
                try {
                    failures = policy.failuresAfterDrop(0, failures);
                    firstAttemptFailed = firstAttempt.getCount() > 0;
                    // 首连失败且 failOnStartupError：插件即将 FAILED——继续重试只会拉起僵尸进程
                    giveUp = policy.budgetExhausted(failures)
                            || (firstAttemptFailed && options.failOnStartupError())
                            || !options.reconnectEnabled();
                    if (giveUp) {
                        state = State.GAVE_UP;
                    } else {
                        state = State.BACKOFF;
                    }
                } finally {
                    lock.unlock();
                }
                if (firstAttemptFailed) {
                    firstFailure = e;
                    firstAttempt.countDown();
                }
                if (giveUp) {
                    listener.onGaveUp();
                    log.warn("MCP 服务器 [{}] 连接失败（第 {} 次），停止重连", server, failures, e);
                    return;
                }
                log.warn("MCP 服务器 [{}] 连接失败（第 {} 次），{}ms 后重试",
                        server, failures, policy.backoffDelayMs(failures), e);
            }
            backoffSleep();
        }
    }

    /** 建立一次连接：启动 server 进程并完成 MCP 初始化握手。 */
    private void connectOnce() {
        ServerParameters params = ServerParameters.builder(options.command())
                .args(options.args())
                .env(options.env())
                .build();
        StdioClientTransport newTransport = new StdioClientTransport(params);
        McpSyncClient newClient = McpClient.sync(newTransport)
                .requestTimeout(Duration.ofMillis(options.requestTimeoutMs()))
                // 远端工具清单变更 → 自动重同步（SDK 已完成 listTools，回调收到的是全量清单）
                .toolsChangeConsumer(listener::onToolsChanged)
                .build();
        try {
            newClient.initialize();
        } catch (Exception e) {
            try {
                newClient.close();   // 同步关闭级联关闭传输与 server 进程——防每次失败尝试泄漏一个进程
            } catch (RuntimeException ignored) {
                // 进程已死的场景：关闭失败无碍
            }
            throw e;
        }
        transport = newTransport;
        client = newClient;
    }

    /** 断连后的退避睡眠：可被 dispose 中断。 */
    private void backoffSleep() {
        try {
            Thread.sleep(policy.backoffDelayMs(failures));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 关闭当前客户端并置空引用；关闭错误只记 warn（旧客户端的资源随进程退出）。 */
    private void closeClientQuietly() {
        McpSyncClient current = client;
        client = null;
        transport = null;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException e) {
                log.warn("MCP 服务器 [{}] 旧客户端关闭失败（忽略）", options.serverName(), e);
            }
        }
    }

    /** 状态快照（锁内）。 */
    State state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    /** 首连失败原因；未失败或已成功返回 null。 */
    Exception firstFailure() {
        return firstFailure;
    }

    /** 停止一切：置 STOPPED、中断循环线程、关闭当前连接。幂等。 */
    void stop() {
        closed = true;
        lock.lock();
        try {
            state = State.STOPPED;
        } finally {
            lock.unlock();
        }
        // 先中断：循环线程可能正阻塞在 awaitForExit，关连接（可能等对端）不该挡住它退出
        Thread t = loopThread;
        if (t != null) {
            t.interrupt();
        }
        closeClientQuietly();
    }
}
