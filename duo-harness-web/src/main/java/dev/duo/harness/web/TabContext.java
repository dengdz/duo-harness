package dev.duo.harness.web;

import dev.duo.harness.agent.ChatAgent;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.session.Session;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 标签上下文（M28 工单 06 从 WebFace 拆出为同包顶层类）：多会话并存的最小单元——
 * 换绑/重建 agent/单飞标志都以它为界（M24 工单 07，ADR-0026 决策五延伸）。
 * 字段包私有：同包协作类（WebFace/WebTabs/WebEndpoints）按域直接读写。
 */
final class TabContext {

    /**
     * 当前 turn 的标签（M24 工单 07）：startAgentTurn 的专属虚拟线程入口置位、收口清除；
     * 工具并发池为 turn 内新建的虚拟线程（InheritableThreadLocal 跨创建继承），池线程上的
     * ask（web_fetch 只读档 ask 语义等）归属不丢。静态：WebPlugin 经 {@code face::currentSession}
     * 共享的会话供给方与 WebAnswerer 的挂起项归属解析器都要读到它。
     */
    static final InheritableThreadLocal<String> TURN_TAB = new InheritableThreadLocal<>();

    final String tabId;
    /** 创建序号（后台任务通知路由的确定性归属——owner 无 tab 粒度时取最早标签）。 */
    final long seq;
    /** 绑定会话（换绑时 SSE 监听器随之迁移；volatile 保证跨线程可见）。 */
    volatile Session session;
    /** 对话执行者（换绑重建；volatile 保证跨线程可见）。 */
    volatile ChatAgent agent;
    /** 单飞标志：一次只跑一轮 send 或一个非 busySafe 命令（CLI 单入口同约定）。 */
    final AtomicBoolean busy = new AtomicBoolean(false);
    /** agent send 执行中标志：busySafe 分级的探针（busy 兼作命令互斥，两者分离）。 */
    final AtomicBoolean agentRunning = new AtomicBoolean(false);
    /** 绑定会话的事件监听订阅（换绑先解绑旧的）。 */
    Disposable sseSubscription;

    TabContext(String tabId, long seq, Session session) {
        this.tabId = tabId;
        this.seq = seq;
        this.session = session;
    }

    /** 当前 turn 的标签 id（turn 上下文外为 null——WebAnswerer 归属解析用）。 */
    static String currentTabId() {
        return TURN_TAB.get();
    }
}
