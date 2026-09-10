package dev.duo.harness.tools.internal;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.tools.ApprovalDecision;
import dev.duo.harness.tools.ApprovalPolicyService;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolNotFoundException;
import dev.duo.harness.tools.ToolResult;
import dev.duo.harness.tools.ToolsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具域实现：注册表 + 三段 waterfall 管线。
 *
 * <p>不继承 Service 基类：发布时机由 ToolsPlugin.apply 掌握
 * （provide 返回的注销器即 apply 的清理职责），而非构造即发布。
 * 三段全部经内核事件总线派发（监听器注册即作用域副作用）；
 * 工具异常统一收敛为 error 结果不上抛。</p>
 */
public final class ToolsServiceImpl implements ToolsService {

    private static final Logger log = LoggerFactory.getLogger(ToolsServiceImpl.class);

    /** 发布服务的插件 Context（事件派发经它）。 */
    private final Context owner;
    /** 已注册工具（注册名 → 定义）。 */
    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    /** @param owner 发布服务的插件 Context */
    public ToolsServiceImpl(Context owner) {
        this.owner = owner;
    }

    @Override
    public Disposable register(Context registrant, ToolDefinition definition) {
        Objects.requireNonNull(registrant, "registrant");
        Objects.requireNonNull(definition, "definition");
        String name = definition.name();
        if (name == null || name.isBlank()) {
            throw new PluginException("工具名不能为空（definition.name()）");
        }
        // 先挂注册方生命周期（作用域已销毁时此处抛出，注册表不进新条目防泄漏），
        // 重名冲突时再摘掉刚挂的 effect
        Disposable removal = registrant.effect(() -> tools.remove(name, definition));
        if (tools.putIfAbsent(name, definition) != null) {
            try {
                removal.dispose();
            } catch (Exception cleanup) {
                // 摘除失败无碍：残留 effect 只会移除一个不存在的条目（幂等）
            }
            throw new PluginException("工具 \"" + name + "\" 已被注册，拒绝重复注册");
        }
        return removal;
    }

    @Override
    public ToolResult execute(String toolName, JsonNode args) {
        if (toolName == null || toolName.isBlank()) {
            throw new ToolNotFoundException("工具名不能为空");
        }
        ToolDefinition tool = tools.get(toolName);
        if (tool == null) {
            throw new ToolNotFoundException("工具 \"" + toolName + "\" 未注册");
        }
        ToolExecution execution = new ToolExecution(toolName, args);
        // 工具自身声明需审批（"或工具可声明"）：先置位，pre-execute 的策略解析者才看得到
        if (tool.requiresApproval()) {
            execution.requestApproval();
        }

        // 一段：准入（默认放行；否决 = 监听器 deny 后不调 next）
        Boolean allowed = owner.waterfall(PRE_EXECUTE, execution, e -> Boolean.TRUE);
        if (!Boolean.TRUE.equals(allowed) || execution.denied()) {
            String reason = execution.denied() ? execution.denyReason() : "被准入监听器否决";
            return ToolResult.error("工具 \"" + toolName + "\" 执行被拒绝: " + reason);
        }

        // 一段 b：审批（ask 三态）。策略解析者是 pre-execute 监听器（审批策略插件注册），
        // 本类不经服务读取——审批是可选治理件，工具域对它有 inject 硬依赖就会在缺它时
        // 永久 PENDING。请求无人解析即"未配置即拒"。
        if (execution.approvalRequested()) {
            ApprovalDecision decision = execution.approvalDecision();
            if (decision == null) {
                decision = ApprovalDecision.deny("审批策略未配置",
                        ApprovalPolicyService.SOURCE_UNCONFIGURED);
            }
            log.info("审批决策：工具={} 结果={} 策略={}", toolName,
                    decision.outcome(), decision.policySource());
            if (decision.outcome() == ApprovalDecision.Outcome.DENY) {
                return ToolResult.error("工具 \"" + toolName + "\" 执行被拒绝: "
                        + decision.reason() + "（策略: " + decision.policySource() + "）");
            }
        }

        // 二段：本体（around 终端 = 工具 execute；超时/重试包装监听器挂此段）
        try {
            owner.waterfall(EXECUTE, execution, e -> {
                e.setResult(tool.execute(e));
                return Boolean.TRUE;
            });
        } catch (Exception e) {
            execution.markError("工具 \"" + toolName + "\" 执行失败: " + rootMessage(e));
        }

        // 三段：结果治理（监听器可改写结果或转错误形态）
        owner.waterfall(POST_EXECUTE, execution, e -> Boolean.TRUE);

        return new ToolResult(execution.result(), execution.resultIsError());
    }

    /** 瀑布包装会层层包异常：报错取最深 cause，呈现工具本体的原始信息。 */
    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.toString();
    }
}
