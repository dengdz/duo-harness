package dev.duo.harness.agent.subagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.agent.subagent.SubagentManager;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.util.Objects;

/**
 * send_message 工具（ADR-0015 决策 2 控制面）：给运行中的子代理补充指示
 * （写入子会话，下一轮生效——中途纠偏不推倒重来）；给空闲/失败的子代理开启
 * 新一轮（指示即新任务）。不进子模板可用集（治理权归父，工单 02 强制过滤）。
 */
public final class SendMessageTool implements ToolDefinition {

    /** 工具名（模型侧调用名，DSH 同款词汇）。 */
    public static final String NAME = "send_message";

    private final SubagentManager manager;
    private final java.util.function.Supplier<dev.duo.harness.session.Session> currentSession;

    public SendMessageTool(SubagentManager manager,
              java.util.function.Supplier<dev.duo.harness.session.Session> currentSession) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.currentSession = java.util.Objects.requireNonNull(currentSession, "currentSession");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "给子代理补充指示：运行中的子代理会在下一轮看到该指示（中途纠偏）；"
                + "空闲或失败的子代理则以该指示为新任务立即开启新一轮（后台运行）。";
    }

    @Override
    public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {"type":"object","properties":{
                      "agentId":{"type":"string","description":"目标子代理 id（spawn/fork 返回，或 list_agents 查询）"},
                      "message":{"type":"string","description":"要补充的指示或新一轮任务描述，具体明确"}},
                     "required":["agentId","message"]}""");
        } catch (Exception e) {
            throw new IllegalStateException("send_message 参数 schema 内置错误", e);
        }
    }

    @Override
    public Object execute(ToolExecution execution) {
        String agentId = requireText(execution.args(), "agentId");
        String message = requireText(execution.args(), "message");
        requireCurrentSession(agentId);
        return manager.sendMessage(agentId, message);
    }

    /** 会话归属校验：长驻呈现位换绑后，旧会话的子代理对新会话不可达（治理边界）。 */
    private void requireCurrentSession(String agentId) {
        var session = currentSession.get();
        if (session == null) {
            throw new PluginException(NAME + ": 无可用父会话（装配不完整）");
        }
        var entry = manager.byId(agentId)
                .orElseThrow(() -> new PluginException("子代理不存在: " + agentId));
        if (!entry.parentSessionId().equals(session.id())) {
            throw new PluginException("子代理 " + agentId + " 属于会话 " + entry.parentSessionId()
                    + "，不属于当前会话 " + session.id());
        }
    }

    private static String requireText(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new PluginException(NAME + " 缺少必填参数 " + field);
        }
        return node.asText();
    }
}
