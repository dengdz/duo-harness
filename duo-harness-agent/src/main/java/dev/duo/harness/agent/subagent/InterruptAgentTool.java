package dev.duo.harness.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.util.Objects;

/**
 * interrupt_agent 工具（ADR-0015 决策 2 控制面）：中止跑偏的子代理——置中断
 * 标志并打断后台线程（协作式中止），终局在子会话留可审计的中止痕迹、父会话
 * 收到"已被中止"回流。不进子模板可用集（工单 02 强制过滤）。
 */
public final class InterruptAgentTool implements ToolDefinition {

    /** 工具名（模型侧调用名，DSH 同款词汇）。 */
    public static final String NAME = "interrupt_agent";

    private final SubagentManager manager;

    public InterruptAgentTool(SubagentManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "中止一个正在运行的子代理（跑偏或不再需要时止损）。子代理会尽快停止，"
                + "不产出结果；已完成或已中止的子代理无需也无法中止。";
    }

    @Override
    public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {"type":"object","properties":{
                      "agentId":{"type":"string","description":"要中止的子代理 id"}},
                     "required":["agentId"]}""");
        } catch (Exception e) {
            throw new IllegalStateException("interrupt_agent 参数 schema 内置错误", e);
        }
    }

    @Override
    public Object execute(ToolExecution execution) {
        String agentId = requireText(execution.args(), "agentId");
        return manager.interrupt(agentId);
    }

    private static String requireText(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new PluginException(NAME + " 缺少必填参数 " + field);
        }
        return node.asText();
    }
}
