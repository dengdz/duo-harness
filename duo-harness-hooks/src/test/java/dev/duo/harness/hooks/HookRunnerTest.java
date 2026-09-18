package dev.duo.harness.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钩子进程执行器用例（真进程，纯单元）：stdin 载荷送达、退出码与 stderr 捕获、
 * exec 直启形态、超时终止、启动失败三态。命令全部即退型（echo/cat/true），不留
 * 后台进程。
 */
class HookRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：HookRunnerTest —— 钩子进程执行：stdin 载荷、退出码/stderr、"
                + "exec 直启、超时终止、启动失败（5 用例） ===");
    }

    @Test
    void payloadReachesStdinAndExitZeroCollected() {
        // cat 读 stdin 原样吐出：载荷送达 + exit 0 + stdout 捕获
        var payload = MAPPER.createObjectNode().put("hook_event_name", "PreToolUse");
        HookHandler handler = new HookHandler("cat", List.of(), Duration.ofSeconds(10));
        HookRunner.Outcome outcome = HookRunner.run(handler, payload);
        assertTrue(outcome.produced());
        assertEquals(0, outcome.exitCode());
        assertEquals(payload.toString(), outcome.stdout().strip());
        assertFalse(outcome.timedOut());
    }

    @Test
    void exitTwoWithStderrCaptured() {
        HookHandler handler = new HookHandler("echo 阻断原因 >&2; exit 2", List.of(),
                Duration.ofSeconds(10));
        HookRunner.Outcome outcome = HookRunner.run(handler, JsonNodeFactory.instance.objectNode());
        assertTrue(outcome.produced());
        assertEquals(2, outcome.exitCode());
        assertEquals("阻断原因", outcome.stderr().strip());
    }

    @Test
    void argsPresentUsesExecFormWithoutShell() {
        // exec 直启：/bin/echo 与 args 直接 spawn（不经 sh -c），多词参数原样到达
        HookHandler handler = new HookHandler("/bin/echo", List.of("hello world", "second"),
                Duration.ofSeconds(10));
        HookRunner.Outcome outcome = HookRunner.run(handler, JsonNodeFactory.instance.objectNode());
        assertTrue(outcome.produced());
        assertEquals(0, outcome.exitCode());
        assertEquals("hello world second", outcome.stdout().strip());
    }

    @Test
    void timeoutKillsProcessAndReportsTimedOut() {
        HookHandler handler = new HookHandler("sleep 30", List.of(), Duration.ofMillis(200));
        HookRunner.Outcome outcome = HookRunner.run(handler, JsonNodeFactory.instance.objectNode());
        assertFalse(outcome.produced(), "超时为放行面");
        assertTrue(outcome.timedOut());
        assertTrue(outcome.diagnosis(handler.command()).contains("超时"));
    }

    @Test
    void execFormMissingBinaryReportsStartFailure() {
        // exec 形态 + 不存在的可执行文件 → ProcessBuilder IOException（启动失败三态）
        HookHandler handler = new HookHandler("/nonexistent-binary-xyz", List.of("a"),
                Duration.ofSeconds(10));
        HookRunner.Outcome outcome = HookRunner.run(handler, JsonNodeFactory.instance.objectNode());
        assertFalse(outcome.produced());
        assertTrue(outcome.startFailure() != null
                && outcome.startFailure().contains("进程启动失败"), outcome.diagnosis(handler.command()));
    }
}
