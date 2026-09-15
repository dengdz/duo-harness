package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.duo.harness.tools.ToolExecution;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bash 工具行为矩阵（M12-03）：全新进程与 workspace 根 cwd、env 硬化、双流分离与
 * 截断、退出码 marker（非零不是错误）、超时 clamp 与进程树终止。
 */
class FsBashToolTest {

    @TempDir
    Path tempDir;

    private Path ws;
    private FsBashTool tool;

    @BeforeAll
    static void 套件叙述() {
        System.out.println("\n=== 套件：FsBashToolTest —— bash：全新进程/env 硬化/stdin 空设备/超时 clamp/"
                + "输出截断/退出码 marker（10 用例） ===");
    }

    @BeforeEach
    void setUp() throws IOException {
        ws = tempDir.resolve("ws");
        Files.createDirectories(ws);
        tool = new FsBashTool(new WorkspacePolicy(ws, WorkspacePolicy.Mode.WORKSPACE_WRITE));
    }

    private static JsonNode json(String text) {
        try { return new ObjectMapper().readTree(text); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** 执行一次（命令内不用双引号，参数 JSON 保持字面可读）。 */
    private String run(String argsJson) {
        return tool.execute(new ToolExecution("bash", json(argsJson)));
    }

    @Test
    void cwdIsWorkspaceRootAndWritesLandInside() throws IOException {
        String result = run("{\"command\":\"pwd -P; touch made-in-ws.txt\"}");
        assertTrue(result.contains(ws.toRealPath().toString()),
                "工作目录固定 workspace 根: " + result);
        assertTrue(Files.exists(ws.resolve("made-in-ws.txt")), "命令产物落在 workspace 根下");
        assertTrue(result.contains("[exit code: 0]"), result);
    }

    @Test
    void environmentIsHardened() {
        String result = run("{\"command\":\"echo $NO_COLOR/$TERM/$PAGER\"}");
        assertTrue(result.contains("1/dumb/cat"), "NO_COLOR / TERM=dumb / PAGER=cat: " + result);
    }

    @Test
    void eachCallIsAFreshProcess() {
        run("{\"command\":\"export DUO_BASH_TEST_MARK=1; echo 设置完成\"}");
        String second = run("{\"command\":\"echo ${DUO_BASH_TEST_MARK:-unset}\"}");
        assertTrue(second.contains("unset"), "shell 状态不跨调用保留（每次全新进程）: " + second);
    }

    @Test
    void stdoutAndStderrAreSeparated() {
        String result = run("{\"command\":\"echo 标准输出; echo 标准错误 >&2\"}");
        assertTrue(result.contains("标准输出"), result);
        assertTrue(result.contains("[stderr]\n标准错误"), "stderr 带标题分段: " + result);
        assertTrue(result.indexOf("标准输出") < result.indexOf("[stderr]"), "stdout 段在前: " + result);
    }

    @Test
    void nonZeroExitIsMarkerInNormalResult() {
        String result = run("{\"command\":\"echo 先有输出; exit 3\"}");
        assertTrue(result.contains("先有输出"), "退出前的输出仍回填: " + result);
        assertTrue(result.contains("[exit code: 3]"), "非零退出 marker: " + result);
        assertFalse(result.contains("[bash 错误]"), "非零退出不转错误形态: " + result);
    }

    @Test
    void eachStreamIsTruncatedWithOmittedCount() {
        String result = run("{\"command\":\"printf '%120000s' '' | tr ' ' 'a'; "
                + "printf '%120000s' '' | tr ' ' 'b' >&2\"}");
        assertTrue(result.contains("[stdout truncated: 20000 chars omitted"), "stdout 截断计数: " + result);
        assertTrue(result.contains("[stderr truncated: 20000 chars omitted"), "stderr 截断计数: " + result);
        assertTrue(result.contains("narrow the command"), "截断 marker 带补救方向: " + result);
        assertTrue(result.length() < 210_000, "结果有界（单流 100000 护栏）: " + result.length());
        assertTrue(result.contains("[exit code: 0]"), result);
    }

    @Test
    void backgroundProcessHoldingTheStreamIsMarkedIncomplete() {
        // 命令已退出但后台任务仍持有管道：读线程 2s 收不了尾——已读部分明示"可能不完整"，
        // 不静默当全量交出去（模型据 marker 自行决定是否收尾后台任务）
        String result = run("{\"command\":\"sleep 3 & echo 先有输出\",\"timeoutMs\":5000}");
        assertTrue(result.contains("先有输出"), result);
        assertTrue(result.contains("[exit code: 0]"), "命令本体正常退出: " + result);
        assertTrue(result.contains("[output may be incomplete"), "后台进程持有流时明示: " + result);
    }

    @Test
    void stdinIsEmptySoReadersExitAtOnce() {
        // 读 stdin 的命令立即 EOF（接 /dev/null）：既不会挂到超时，也不会偷吃 REPL 输入
        String result = run("{\"command\":\"cat; echo done\",\"timeoutMs\":1000}");
        assertTrue(result.contains("done"), "stdin 空设备——cat 立即 EOF: " + result);
        assertFalse(result.contains("[timed out]"), "未挂起等 stdin: " + result);
    }

    @Test
    void timeoutKillsProcessTreeAndMarksResult() throws Exception {
        long start = System.currentTimeMillis();
        String result = run("{\"command\":\"sleep 2; touch should-not-exist.txt\",\"timeoutMs\":300}");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(result.contains("[timed out]"), "超时 marker: " + result);
        assertTrue(elapsed < 1_500, "低值超时即刻生效（未等命令自然结束）: " + elapsed + "ms");

        // 命令树被终止：睡醒后本该创建的产物不出现（只杀 bash 会留下孤儿子进程）
        Thread.sleep(2_200);
        assertFalse(Files.exists(ws.resolve("should-not-exist.txt")), "进程树已终止，后续命令未执行");
    }

    @Test
    void timeoutClampMatrix() {
        assertEquals(120_000, FsBashTool.timeoutFor(json("{\"command\":\"x\"}")), "缺省 120s");
        assertEquals(300, FsBashTool.timeoutFor(json("{\"command\":\"x\",\"timeoutMs\":300}")), "低值透传");
        assertEquals(600_000, FsBashTool.timeoutFor(json("{\"command\":\"x\",\"timeoutMs\":900000}")), "上限 600s");
        assertEquals(120_000, FsBashTool.timeoutFor(json("{\"command\":\"x\",\"timeoutMs\":0}")), "非正数按缺省");
        assertEquals(120_000, FsBashTool.timeoutFor(json("{\"command\":\"x\",\"timeoutMs\":\"快\"}")), "非数值按缺省");
    }
}