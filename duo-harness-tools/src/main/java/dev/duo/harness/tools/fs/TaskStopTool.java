package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

/**
 * task-stop 工具（M23 工单 04，ADR-0025 决策二）：终止后台任务——复用前台 bash
 * 杀树（子孙先 SIGTERM、宽限后 SIGKILL）；幂等：已结束任务返回其终态说明而非报错。
 */
public final class TaskStopTool implements ToolDefinition {

    public static final String NAME = "task-stop";

    private final BackgroundTaskRegistry registry;

    public TaskStopTool(BackgroundTaskRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "终止后台任务（run_in_background 启动）——杀进程树。幂等：任务已结束时返回其终态。";
    }
    @Override public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"type\":\"object\",\"properties\":{"
                + "\"taskId\":{\"type\":\"string\",\"description\":\"run_in_background 返回的任务 id\"}"
                + "},\"required\":[\"taskId\"]}");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Override public boolean isConcurrencySafe(JsonNode args) { return false; }

    @Override public String execute(ToolExecution exec) {
        String taskId = exec.args().path("taskId").asText("");
        BackgroundTask task = registry.get(taskId).orElse(null);
        if (task == null) {
            return error("后台任务不存在: " + taskId);
        }
        if (task.isCompleted()) {
            return "[后台任务 " + task.taskId() + "] 已于先前结束，无需终止：" + task.terminalLine()
                    + "\n（最终输出可用 task-output 查看）";
        }
        task.terminate();
        return "[后台任务 " + task.taskId() + "] 已终止：" + task.command() + "\n"
                + task.terminalLine();
    }

    private static String error(String msg) { return "[task-stop 错误] " + msg; }
}
