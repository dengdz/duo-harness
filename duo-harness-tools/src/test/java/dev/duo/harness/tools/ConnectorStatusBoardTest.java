package dev.duo.harness.tools;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接器状态板用例（M24 工单 05，ADR-0026 决策四）：更新覆盖、快照稳定排序、
 * 耗尽通知全订阅者触达、视图桥接自引用。
 */
class ConnectorStatusBoardTest {

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：ConnectorStatusBoardTest —— 连接器状态板：更新覆盖、快照排序、"
                + "耗尽通知触达（3 用例） ===");
    }

    @Test
    void updateOverwritesAndSnapshotStableSorted() {
        ConnectorStatusBoard board = new ConnectorStatusBoard();
        board.update("zeta", "CONNECTED", "已连接");
        board.update("alpha", "GAVE_UP", "重连预算耗尽，工具已下线");
        board.update("alpha", "CONNECTED", "已连接"); // 覆盖式——最新为准

        List<ConnectorStatusBoard.Connector> snapshot = board.snapshot();
        assertEquals(2, snapshot.size());
        assertEquals("alpha", snapshot.get(0).server(), "按 server 名稳定排序");
        assertEquals("CONNECTED", snapshot.get(0).state());
        assertEquals("已连接", snapshot.get(0).detail());
    }

    @Test
    void gaveUpNoticeReachesAllSubscribers() {
        ConnectorStatusBoard board = new ConnectorStatusBoard();
        AtomicInteger hits = new AtomicInteger();
        board.onGaveUp(n -> hits.incrementAndGet());
        board.onGaveUp(n -> hits.incrementAndGet());

        board.fireGaveUp("MCP 服务器 [files] 重连预算耗尽，相关工具已下线");
        assertEquals(2, hits.get(), "全部订阅者触达");
    }

    @Test
    void viewBridgeReturnsSelf() {
        ConnectorStatusBoard board = new ConnectorStatusBoard();
        assertSame(board, board.connectorStatus(), "视图桥接方法返回自身（方法名即服务名）");
    }
}
