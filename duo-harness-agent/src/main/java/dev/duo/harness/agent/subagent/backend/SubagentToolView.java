package dev.duo.harness.agent.subagent.backend;

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
 * 部署者审定范围），{@code execute} 经审批钉死策略前置检查后委托共享注册表——
 * 三段管线（审批/guard/输出契约）对子代理调用自然生效，工具域注册机制零改动
 * （ADR-0015 Consequences）。子代理不注册工具、不挂 guard——对应操作拒绝。
 *
 * <p>审批钉死（M16 工单 03）：需审批的调用在委托前确定性拒绝（理由随工具结果
 * 回传子代理），不进入审批管线挂起等待人工——交互请求无法到达子代理上下文，
 * 挂起即永久卡死——limitations M15#3 随本修复消除）。</p>
 */
final class SubagentToolView implements ToolsService {

    private final ToolsService delegate;
    private final Set<String> allowed;
    private final PinnedApprovalPolicy approvalPolicy;

    SubagentToolView(ToolsService delegate, List<String> allowed, PinnedApprovalPolicy approvalPolicy) {
        this.delegate = delegate;
        this.allowed = Set.copyOf(allowed);
        this.approvalPolicy = approvalPolicy;
    }

    @Override
    public List<ToolDefinition> list() {
        return delegate.list().stream()
                .filter(def -> allowed.contains(def.name()))
                .toList();
    }

    @Override
    public ToolResult execute(String toolName, JsonNode args) {
        // 审批钉死：委托前查工具的 requiresApproval 声明，按注入策略裁决——
        // 声明归声明者、裁决归策略（与工具域"声明/裁决分离"同构），视图不含策略细节
        var definition = list().stream()
                .filter(def -> def.name().equals(toolName)).findFirst();
        if (definition.isPresent() && !approvalPolicy.allows(definition.get())) {
            return ToolResult.error(approvalPolicy.denialReason(toolName));
        }
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
