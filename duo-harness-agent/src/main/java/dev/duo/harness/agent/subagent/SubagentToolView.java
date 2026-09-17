package dev.duo.harness.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.tools.GuardCheck;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsService;

import java.util.List;
import java.util.Set;

/**
 * 子 agent 的工具域视图：{@code list()} 只见模板可用集（模型可点名面收窄到
 * 部署者审定范围），{@code execute} 原样委托共享注册表——三段管线（审批/guard/
 * 输出契约）对子代理调用自然生效，工具域注册机制零改动（ADR-0015 Consequences）。
 * 子代理不注册工具、不挂 guard——对应操作拒绝。
 */
final class SubagentToolView implements ToolsService {

    private final ToolsService delegate;
    private final Set<String> allowed;

    SubagentToolView(ToolsService delegate, List<String> allowed) {
        this.delegate = delegate;
        this.allowed = Set.copyOf(allowed);
    }

    @Override
    public List<ToolDefinition> list() {
        return delegate.list().stream()
                .filter(def -> allowed.contains(def.name()))
                .toList();
    }

    @Override
    public ToolResult execute(String toolName, JsonNode args) {
        return delegate.execute(toolName, args);
    }

    @Override
    public Disposable register(Context registrant, ToolDefinition definition) {
        throw new UnsupportedOperationException("子代理不能注册工具（模板制：配置权在部署者）");
    }

    @Override
    public Disposable guard(Context registrant, GuardCheck check) {
        throw new UnsupportedOperationException("子代理不能注册 guard");
    }
}
