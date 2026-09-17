package dev.duo.harness.agent.subagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.agent.subagent.SubagentManager;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.util.Objects;

/**
 * list_agents 工具（ADR-0015 决策 2 控制面）：列出全部子代理的 id / 模板 / 状态
 * （运行中 / 空闲 / 失败 / 中断）——分解了几个任务、各自进展心中有数。
 * 不进子模板可用集（工单 02 强制过滤）。
 */
public final class ListAgentsTool implements ToolDefinition {

    /** 工具名（模型侧调用名，DSH 同款词汇）。 */
    public static final String NAME = "list_agents";

    private final SubagentManager manager;
    private final java.util.function.Supplier<dev.duo.harness.session.Session> currentSession;

    public ListAgentsTool(SubagentManager manager,
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
        return "查看全部子代理的状态清单（id、模板、状态：运行中/空闲/失败/中断），"
                + "用于掌握任务分解的整体进展；空闲的子代理可用 send_message 开新轮。";
    }

    @Override
    public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    "{\"type\":\"object\",\"properties\":{}}");
        } catch (Exception e) {
            throw new IllegalStateException("list_agents 参数 schema 内置错误", e);
        }
    }

    @Override
    public Object execute(ToolExecution execution) {
        StringBuilder text = new StringBuilder();
        var session = currentSession.get();
        if (session == null) {
            throw new PluginException(NAME + ": 无可用父会话（装配不完整）");
        }
        var entries = manager.byParentSession(session.id()); // 只列当前父会话名下的（换绑后互不可见）
        if (entries.isEmpty()) {
            return "当前没有子代理。";
        }
        text.append("共 ").append(entries.size()).append(" 个子代理：");
        for (SubagentManager.Entry entry : entries) {
            text.append("\n- ").append(entry.id())
                    .append("（模板 ").append(entry.templateName())
                    .append("，").append(stateLabel(entry.state())).append("）");
        }
        return text.toString();
    }

    /** 状态中文呈现（list_agents 与子任务卡共用的映射源）。 */
    static String stateLabel(SubagentManager.State state) {
        return switch (state) {
            case RUNNING -> "运行中";
            case IDLE -> "空闲";
            case FAILED -> "失败";
            case INTERRUPTED -> "已中断";
        };
    }
}
