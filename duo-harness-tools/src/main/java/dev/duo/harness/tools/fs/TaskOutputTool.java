package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.util.concurrent.TimeUnit;

/**
 * task-output 工具（M23 工单 04，ADR-0025 决策二）：读后台任务输出或等待其完成——
 * block=true（缺省）等至任务结束或超时返回当前状态；block=false 立即快照。
 * 读的是输出尾部窗口（前台 bash 同款护栏）。
 */
public final class TaskOutputTool implements ToolDefinition {

    public static final String NAME = "task-output";
    private static final long DEFAULT_WAIT_MS = 30_000;
    private static final long MAX_WAIT_MS = 600_000;
    private static final int TAIL_CHARS = 32_000;

    private final BackgroundTaskRegistry registry;

    public TaskOutputTool(BackgroundTaskRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "读取后台任务（run_in_background 启动）的输出。block=true（默认）等任务结束"
                + "或超时返回当前状态，block=false 立即返回快照。timeoutMs 等待上限（默认 30000，上限 600000）。";
    }
    @Override public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"type\":\"object\",\"properties\":{"
                + "\"taskId\":{\"type\":\"string\",\"description\":\"run_in_background 返回的任务 id（如 bg-1）\"},"
                + "\"block\":{\"type\":\"boolean\",\"description\":\"true（默认）= 等待任务结束或超时；false = 立即返回快照\"},"
                + "\"timeoutMs\":{\"type\":\"number\",\"description\":\"等待上限毫秒（默认 30000，上限 600000）\"}"
                + "},\"required\":[\"taskId\"]}");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** 读取不写、等待阻塞——保守独占（与 bash 同为审批面外的只读语义，但等待段不该并行占用）。 */
    @Override public boolean isConcurrencySafe(JsonNode args) { return false; }

    @Override public String execute(ToolExecution exec) {
        JsonNode args = exec.args();
        String taskId = args.path("taskId").asText("");
        BackgroundTask task = registry.get(taskId).orElse(null);
        if (task == null) {
            return error("后台任务不存在: " + taskId
                    + "（taskId 以 run_in_background 的返回为准）");
        }
        boolean block = !args.has("block") || args.path("block").asBoolean(true);
        long waitMs = Math.min(
                args.path("timeoutMs").isNumber() && args.path("timeoutMs").asLong() > 0
                        ? args.path("timeoutMs").asLong() : DEFAULT_WAIT_MS,
                MAX_WAIT_MS);
        if (block && !task.isCompleted()) {
            try {
                task.process().waitFor(waitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error("task-output 等待被中断: " + taskId);
            }
        }
        String state = task.state() == BackgroundTask.State.RUNNING
                ? "[运行中]（等待 " + waitMs + "ms 后仍运行；可用 task-stop 终止或再次 task-output 等待）"
                : task.terminalLine();
        String output = task.output();
        String tail = output.length() > TAIL_CHARS
                ? "…（前 " + (output.length() - TAIL_CHARS) + " 字符已省略）\n"
                  + output.substring(output.length() - TAIL_CHARS)
                : output;
        return "[后台任务 " + task.taskId() + "] " + task.command() + "\n" + state
                + (tail.isEmpty() ? "\n（暂无输出）" : "\n" + tail);
    }

    private static String error(String msg) { return "[task-output 错误] " + msg; }
}
