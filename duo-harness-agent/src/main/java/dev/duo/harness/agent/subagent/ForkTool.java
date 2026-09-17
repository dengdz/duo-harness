package dev.duo.harness.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.session.Session;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * fork 工具（ADR-0015 决策 2/4）：父 agent 派生带着当前对话背景的子代理——
 * 父日志的平衡完成轮前缀播种为子会话开头段（含种子边界标记），子任务无须重新
 * 交代前因后果。其余语义同 {@link SpawnTool}（立即返回 id、后台虚拟线程、
 * completed 回流）。
 */
public final class ForkTool implements ToolDefinition {

    /** 工具名（模型侧调用名，DSH 同款词汇）。 */
    public static final String NAME = "fork";

    private final SubagentManager manager;
    private final Supplier<Session> currentSession;

    public ForkTool(SubagentManager manager, Supplier<Session> currentSession) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.currentSession = Objects.requireNonNull(currentSession, "currentSession");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "从当前对话派生子代理：把已有对话背景（到最近一个完整轮为止）带进子代理，"
                + "子任务不用重新交代前因后果（立即返回 agent id，不等待完成）。"
                + "适合把当前讨论中的一个分支深挖下去——例如让子代理拿着已确定的方案去做耗时执行。"
                + "注意：子代理拿到的是背景快照，之后本对话的进展它看不到，也不会跨任务记忆；"
                + "task 仍应是边界清晰、有限步内可完成的一件小事（目标与期望产出写明确），"
                + "大任务请拆成多个子代理分别 fork。template 必须从已配置的模板中点名。"
                + "用 list_agents 查看各子代理进展，用 send_message 补充指示。"
                + "可用模板：" + String.join("、", manager.templateNames()) + "。";
    }

    @Override
    public JsonNode parameters() {
        try {
            var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                    .put("type", "object");
            var props = schema.putObject("properties");
            var template = props.putObject("template")
                    .put("description", "子代理模板名（部署方预定义的能力边界）");
            var enumNames = template.putArray("enum");
            manager.templateNames().forEach(enumNames::add);
            props.putObject("task")
                    .put("description", "子任务目标与期望产出（对话背景自动继承）");
            schema.putArray("required").add("template").add("task");
            return schema;
        } catch (Exception e) {
            throw new IllegalStateException("fork 参数 schema 内置错误", e);
        }
    }

    @Override
    public Object execute(ToolExecution execution) {
        String template = requireText(execution.args(), "template");
        String task = requireText(execution.args(), "task");
        Session parent = currentSession.get();
        if (parent == null) {
            throw new PluginException("fork 无可用父会话（装配不完整）");
        }
        String agentId = manager.fork(parent, template, task);
        return "{\"agentId\":\"" + agentId + "\",\"template\":\"" + template + "\",\"status\":\"started\"}";
    }

    /** 必填文本参数严格读取：缺失或空白点名（模型可见错误后自行补参重调）。 */
    private static String requireText(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new PluginException(NAME + " 缺少必填参数 " + field);
        }
        return node.asText();
    }
}
