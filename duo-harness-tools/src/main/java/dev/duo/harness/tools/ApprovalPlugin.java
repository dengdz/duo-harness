package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.events.WaterfallListener;
import dev.duo.harness.tools.internal.AlwaysDenyPolicy;
import dev.duo.harness.tools.internal.ApprovalGate;
import dev.duo.harness.tools.internal.AutoApprovePolicy;

import java.util.HashSet;
import java.util.Set;

/**
 * 审批策略插件：按配置选择预设策略，发布为 "approval" 服务，
 * 并注册 {@code tools/pre-execute} 的策略解析监听器。
 *
 * <p>配置（内核严格绑定要求 config 块在场，块内字段可省）：</p>
 * <pre>{@code
 * config:
 *   policy: always-deny      # 或 auto-approve（policy 省略即 always-deny）
 *   allowedTools:            # auto-approve 专用（省略即空集）
 *     - echo
 * }</pre>
 *
 * <p>交互式审批（人逐次作答）不在此插件：挂 {@link InteractiveApprovalPlugin}
 * （二选一，同一服务名占坑互斥），其 inject 声明 answers 并委托交互 seam。</p>
 *
 * <p>解析监听器只裁决**被声明的 ask**（{@link ToolDefinition#requiresApproval()} 或
 * pre-execute 监听器的 {@link ToolExecution#requestApproval()}）——这是 spec 的
 * "治理插件或工具可声明'此调用需要审批'；ask 委托审批策略服务裁决"：
 * 声明归声明者，裁决归策略，两者分离。未被声明的调用不被审批介入。</p>
 *
 * <p>监听器以 {@code next.invoke} 先行（around 语义）再裁决：内层治理者的
 * 否决与 ask 声明都已落定，且与自身注册次序无关。不声明 inject——审批是可选
 * 治理件，与工具域经事件总线协作（服务读取会构成硬依赖，缺失时工具域不可用）。</p>
 */
public final class ApprovalPlugin implements Plugin<JsonNode> {

    /** 缺省策略：恒拒——"未配置即拒"的具象。 */
    private static final String DEFAULT_POLICY = AlwaysDenyPolicy.SOURCE;

    @Override
    public Class<JsonNode> configType() {
        return JsonNode.class;
    }

    @Override
    public Disposable apply(Context ctx, JsonNode config) {
        ApprovalPolicyService policy = createPolicy(ctx, config);
        Disposable published = ctx.provide(ApprovalPolicyService.SERVICE_NAME, policy);
        Disposable gate = ctx.on(ToolsService.PRE_EXECUTE, gateFor(policy));
        return () -> {
            gate.dispose();
            published.dispose();
        };
    }

    /** 策略解析监听器：内层先跑，内层否决或不涉审批则不介入，否则委托策略裁决。 */
    private static WaterfallListener<ToolExecution, Boolean> gateFor(ApprovalPolicyService policy) {
        return ApprovalGate.gateFor(policy);
    }

    private static ApprovalPolicyService createPolicy(Context ctx, JsonNode config) {
        String policy = config.hasNonNull("policy") ? config.get("policy").asText() : DEFAULT_POLICY;
        return switch (policy) {
            case AlwaysDenyPolicy.SOURCE -> new AlwaysDenyPolicy();
            case AutoApprovePolicy.SOURCE -> new AutoApprovePolicy(parseAllowedTools(config));
            default -> throw new IllegalArgumentException(
                    "未知审批策略: " + policy + "（支持: "
                            + AlwaysDenyPolicy.SOURCE + " / " + AutoApprovePolicy.SOURCE
                            + "；交互式审批挂 " + InteractiveApprovalPlugin.class.getSimpleName() + "）");
        };
    }

    /** 白名单（auto-approve 专用）；省略或非数组即空集——空集下恒拒。 */
    private static Set<String> parseAllowedTools(JsonNode config) {
        JsonNode list = config.get("allowedTools");
        if (list == null || !list.isArray()) {
            return Set.of();
        }
        Set<String> tools = new HashSet<>();
        list.forEach(n -> tools.add(n.asText()));
        return Set.copyOf(tools);
    }
}
