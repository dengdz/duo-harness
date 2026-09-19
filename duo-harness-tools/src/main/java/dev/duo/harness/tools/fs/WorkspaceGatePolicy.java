package dev.duo.harness.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.tools.ApprovalDecision;
import dev.duo.harness.tools.ApprovalPolicyService;

import java.nio.file.Path;

/**
 * 档位前置裁决策略（ADR-0012"权限预设与审批联动"）：先按 workspace 档位判定，
 * ALLOW 短路放行（免审批），ASK 委托内层策略（交互回答者瀑布）由人作答。
 *
 * <p>目标路径取自参数 {@code path} 字段；缺失/空白交 {@code null}——由
 * {@link WorkspacePolicy#decide} 按保守 ask 处理（spec：判定解析失败保守 ask）。
 * 未知工具名同样保守 ask——档位闸门只对已声明的读写类短路，不扩大免审面。</p>
 *
 * <p>线程约定：判定只读不可变依赖（workspace volatile mode、内层策略无状态），
 * 可被工具循环并发调用。</p>
 */
public final class WorkspaceGatePolicy implements ApprovalPolicyService {

    /** 档位放行的审计署名（与内层策略署名区分，决策日志可指认"档位放行"）。 */
    public static final String SOURCE = "workspace";

    private final ApprovalPolicyService inner;
    private final WorkspacePolicy workspace;

    public WorkspaceGatePolicy(ApprovalPolicyService inner, WorkspacePolicy workspace) {
        this.inner = inner;
        this.workspace = workspace;
    }

    @Override
    public ApprovalDecision decide(String toolName, JsonNode args) {
        Path targetPath = null;
        if (args != null && args.hasNonNull("path")) {
            targetPath = workspace.resolveInWorkspaceOrNull(args.get("path").asText(""));
        }
        if (workspace.decide(toolName, targetPath) == WorkspacePolicy.Decision.ALLOW) {
            return ApprovalDecision.allow(SOURCE);
        }
        return inner.decide(toolName, args);
    }

    /** 携发起呈现位版：档位判定与标记无关，ask 委托内层时原样转发（亲和路由）。 */
    @Override
    public ApprovalDecision decide(String toolName, JsonNode args, String presenterId) {
        Path targetPath = null;
        if (args != null && args.hasNonNull("path")) {
            targetPath = workspace.resolveInWorkspaceOrNull(args.get("path").asText(""));
        }
        if (workspace.decide(toolName, targetPath) == WorkspacePolicy.Decision.ALLOW) {
            return ApprovalDecision.allow(SOURCE);
        }
        return inner.decide(toolName, args, presenterId);
    }
}
