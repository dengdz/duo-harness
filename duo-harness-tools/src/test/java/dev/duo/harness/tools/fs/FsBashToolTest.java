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
                + "输出截断/退出码 marker、run_in_background/task-output/task-stop、输出三层与 spill（18 用例） ===");
    }

    private BackgroundTaskRegistry registry;
    private BashOutputConfig outputConfig = BashOutputConfig.DEFAULTS;
    private String realDuoHome;

    @BeforeEach
    void setUp() throws IOException {
        ws = tempDir.resolve("ws");
        Files.createDirectories(ws);
        registry = new BackgroundTaskRegistry();
        // spill 落 Duo home 临时区——测试用 duo.home 系统属性重定向（解析优先级第一），
        // 不污染真实 ~/.duo
        realDuoHome = System.getProperty("duo.home");
        System.setProperty("duo.home", tempDir.resolve("duo-home").toString());
        tool = new FsBashTool(new WorkspacePolicy(ws, WorkspacePolicy.Mode.WORKSPACE_WRITE),
                registry, outputConfig);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreDuoHome() {
        if (realDuoHome != null) {
            System.setProperty("duo.home", realDuoHome);
        } else {
            System.clearProperty("duo.home");
        }
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
    void defaultBudgetKeepsLargeOutputBoundedViaSpill() throws IOException {
        // 输出分层（M23 工单 05）取代旧 100k 截断：默认预算下大输出 = 尾窗 + spill
        // 提示，结果体积有界（尾窗 30k 内）且无丢弃（未超帽）
        String result = run("{\"command\":\"seq 1 20000\",\"timeoutMs\":30000}");
        assertTrue(result.contains("[stdout spilled]"), "大输出触发 spill 提示: "
                + result.substring(0, Math.min(400, result.length())));
        assertFalse(result.contains("chars dropped"), "未超帽无丢弃告警");
        assertTrue(result.length() < 40_000, "返回体积有界（尾窗）: " + result.length());
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

    @Test
    void runInBackgroundReturnsTaskIdAndTaskFaceManagesLifecycle() throws Exception {
        // 后台任务端到端（M23 工单 04）：run_in_background 立即返回 taskId → task-output
        // 等待完成读输出 → task-stop 对已结束任务幂等说明；前台超时语义不受影响
        String started = run("{\"command\":\"echo bg-done && sleep 1\",\"run_in_background\":true}");
        assertTrue(started.contains("[后台任务] bg-1 已启动"), started);
        assertTrue(started.contains("task-output"), "返回说明指路 task 面: " + started);

        BackgroundTask task = registry.get("bg-1").orElseThrow();
        // task-output block 等待完成（echo 即输出、sleep 1s 后退出）
        long deadline = System.currentTimeMillis() + 5_000;
        String waited = "";
        while (System.currentTimeMillis() < deadline) {
            waited = new TaskOutputTool(registry).execute(
                    new ToolExecution("task-output", json("{\"taskId\":\"bg-1\",\"block\":true,\"timeoutMs\":2000}")));
            if (waited.contains("[exit code:")) break;
            Thread.sleep(50);
        }
        assertTrue(waited.contains("[exit code: 0]"), "等待后终态含退出码: " + waited);
        assertTrue(waited.contains("bg-done"), "输出含命令产物: " + waited);

        // task-stop 幂等：已结束任务返回终态说明而非报错
        String stopped = new TaskStopTool(registry).execute(
                new ToolExecution("task-stop", json("{\"taskId\":\"bg-1\"}")));
        assertTrue(stopped.contains("已于先前结束"), stopped);
    }

    @Test
    void stopKillsRunningBackgroundTask() throws Exception {
        String started = run("{\"command\":\"sleep 60\",\"run_in_background\":true}");
        assertTrue(started.contains("[后台任务] bg-1 已启动"), started);
        BackgroundTask task = registry.get("bg-1").orElseThrow();
        assertTrue(task.state() == BackgroundTask.State.RUNNING, "启动后运行中");

        String stopped = new TaskStopTool(registry).execute(
                new ToolExecution("task-stop", json("{\"taskId\":\"bg-1\"}")));
        assertTrue(stopped.contains("已终止"), stopped);
        long deadline = System.currentTimeMillis() + 5_000;
        while (task.process().isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(task.process().isAlive(), "杀树生效");
    }

    @Test
    void oversizedOutputSpillsToDiskWithReadbackPath() throws Exception {
        // 输出分层（M23 工单 05）：小 inline 预算注入——大输出超窗部分落 spill 并回传
        // 路径；read 回读 spill 与模型所见历史一致；尾窗保最近输出
        outputConfig = new BashOutputConfig(200, 1_000_000, 32_000);
        registry = new BackgroundTaskRegistry(outputConfig);
        tool = new FsBashTool(new WorkspacePolicy(ws, WorkspacePolicy.Mode.WORKSPACE_WRITE),
                registry, outputConfig); // 覆盖 setUp 的缺省构造（本用例预算注入）

        String result = run("{\"command\":\"seq 1 300\",\"timeoutMs\":30000}");
        assertTrue(result.contains("[stdout spilled]"), "超窗触发 spill 提示: " + result);
        var matcher = java.util.regex.Pattern
                .compile("read 此文件回读全文: (\\S+\\.txt)").matcher(result);
        assertTrue(matcher.find(), "spillPath 回传——resultLen=" + result.length()
                + " spilled行=" + result.contains("[stdout spilled]"));
        String path = matcher.group(1);
        assertTrue(result.contains("300"), "尾窗含最近输出（末尾行号 300 附近）: " + result);
        assertFalse(result.contains("[stdout spill 超帽]"), "未超帽无丢弃告警: " + result);

        // 回读一致性：spill 文件含被裁历史（文件开头即 seq 的第 1 行，无前导换行）
        String spilled = java.nio.file.Files.readString(java.nio.file.Path.of(path));
        assertTrue(spilled.startsWith("1\n2\n3\n"), "spill 含早期被裁历史（1 起头）: "
                + spilled.substring(0, Math.min(30, spilled.length())));
        assertTrue(spilled.contains("\n200\n"), "spill 覆盖到接近尾窗边界");
    }

    @Test
    void spillOverflowWarnsLoudly() throws Exception {
        // 超帽告警不静默（M23 工单 05）：spillMaxChars 小注入——超帽部分丢弃并显式告警
        outputConfig = new BashOutputConfig(100, 500, 32_000);
        registry = new BackgroundTaskRegistry(outputConfig);
        tool = new FsBashTool(new WorkspacePolicy(ws, WorkspacePolicy.Mode.WORKSPACE_WRITE),
                registry, outputConfig); // 覆盖 setUp 的缺省构造

        String result = run("{\"command\":\"seq 1 200\",\"timeoutMs\":30000}");
        assertTrue(result.contains("[stdout spill 超帽]"), "超帽显式告警: " + result);
        assertTrue(result.contains("chars dropped"), "丢弃量可见: " + result);
    }

    @Test
    void smallOutputLeavesNoSpillResidue() throws Exception {
        // 小输出（未超 inline 预算）不创建 spill 文件——无残渣
        outputConfig = new BashOutputConfig(200, 1_000, 32_000);
        registry = new BackgroundTaskRegistry(outputConfig);
        tool = new FsBashTool(new WorkspacePolicy(ws, WorkspacePolicy.Mode.WORKSPACE_WRITE),
                registry, outputConfig); // 覆盖 setUp 的缺省构造
        String result = run("{\"command\":\"echo tiny\",\"timeoutMs\":30000}");
        assertTrue(result.contains("tiny"), result);
        assertFalse(result.contains("[stdout spilled]"), "小输出不触发 spill: " + result);
        var spillDir = java.nio.file.Path.of(System.getProperty("duo.home"),
                "tmp", "bash-spill");
        assertFalse(java.nio.file.Files.exists(spillDir)
                && java.nio.file.Files.list(spillDir).count() > 0, "spill 目录无残渣");
    }

    @Test
    void runInBackgroundWithoutRegistryFailsCleanly() {
        FsBashTool bare = new FsBashTool(new WorkspacePolicy(ws, WorkspacePolicy.Mode.WORKSPACE_WRITE));
        String result = bare.execute(new ToolExecution("bash", json(
                "{\"command\":\"echo x\",\"run_in_background\":true}")));
        assertTrue(result.contains("后台任务注册表未装配"), result);
    }


    @Test
    void taskOutputTailCharsConfigurable() throws Exception {
        // task-output 尾窗可配（M23 工单 05）：小尾窗注入——长输出裁到窗内
        outputConfig = new BashOutputConfig(200, 1_000_000, 50);
        registry = new BackgroundTaskRegistry(outputConfig);
        tool = new FsBashTool(new WorkspacePolicy(ws, WorkspacePolicy.Mode.WORKSPACE_WRITE),
                registry, outputConfig); // 用例内重建：run() 与 outputTool 必须同源注册表
        var outputTool = new TaskOutputTool(registry, outputConfig.taskOutputTailChars());
        String started = run("{\"command\":\"seq 1 50\",\"run_in_background\":true}");
        assertTrue(started.contains("bg-1"), started);
        long deadline = System.currentTimeMillis() + 5_000;
        while (!registry.get("bg-1").orElseThrow().isCompleted()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        String waited = outputTool.execute(new ToolExecution("task-output",
                json("{\"taskId\":\"bg-1\",\"block\":false}")));
        assertTrue(waited.contains("字符已省略"), "尾窗裁剪生效: " + waited);
    }

    @Test
    void outputConfigParsesAndRejects() throws Exception {
        // config.output 段解析（M23 工单 05）：缺席缺省、字段生效、非法点名
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var empty = mapper.createObjectNode();
        assertEquals(BashOutputConfig.DEFAULTS, dev.duo.harness.tools.fs.FsToolsPlugin.parseOutput(empty));
        var full = mapper.readTree("{\"output\":{\"inlineTailChars\":500,"
                + "\"spillMaxChars\":4096,\"taskOutputTailChars\":128}}");
        var parsed = dev.duo.harness.tools.fs.FsToolsPlugin.parseOutput(full);
        assertEquals(500, parsed.inlineTailChars());
        assertEquals(4096, parsed.spillMaxChars());
        assertEquals(128, parsed.taskOutputTailChars());
        var bad = mapper.readTree("{\"output\":{\"inlineTailChars\":-1}}");
        org.junit.jupiter.api.Assertions.assertThrows(dev.duo.harness.core.api.PluginException.class,
                () -> dev.duo.harness.tools.fs.FsToolsPlugin.parseOutput(bad));
    }

}