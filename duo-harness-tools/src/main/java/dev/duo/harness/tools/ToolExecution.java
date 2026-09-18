package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次工具执行的全程载荷：贯穿三段管线（pre-execute / execute / post-execute），
 * 各段监听器经它读参数、否决与改写结果。
 *
 * <p>可变性与线程约定：单次执行内串行传递，不跨线程共享——唯一例外是超时终局
 * （{@link #markTimeout(String)} 由超时监听器线程写入，执行线程可能仍在跑并迟到地
 * 写结果，冻结语义保证迟到的真实结果被丢弃，ADR-0018）。</p>
 */
public final class ToolExecution {

    /** 工具名（本次执行目标）。 */
    private final String toolName;
    /** 调用参数（NullNode 表示无参）。 */
    private final JsonNode args;

    /** pre-execute 否决理由；非 null 即已否决。 */
    private String denyReason;
    /** pre-execute 审批请求标志；true 表示需策略服务裁决（ask 三态）。 */
    private boolean approvalRequested;
    /** 审批裁决结果；null 表示请求尚未被任何解析者裁决。 */
    private ApprovalDecision approvalDecision;
    /** 执行结果；post-execute 段可改写。 */
    private Object result;
    /** 结果是否为错误形态。 */
    private boolean resultIsError;
    /** 超时终局标志：置位后迟到的 setResult 丢弃（超时监听器与执行线程的跨线程契约）。 */
    private boolean resultFrozen;

    public ToolExecution(String toolName, JsonNode args) {
        // 公共类的 null 契约自足：不依赖唯一构造点（ToolsServiceImpl）的先行校验
        this.toolName = java.util.Objects.requireNonNull(toolName, "toolName");
        this.args = args == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : args;
    }

    /** 工具名。 */
    public String toolName() {
        return toolName;
    }

    /** 调用参数（无参为 NullNode，不返回 null）。 */
    public JsonNode args() {
        return args;
    }

    /** pre-execute 监听器否决本次执行；理由将呈现在错误结果中。 */
    public void deny(String reason) {
        this.denyReason = reason;
    }

    /** 是否已被 pre-execute 否决。 */
    public boolean denied() {
        return denyReason != null;
    }

    /** 否决理由（未否决为 null）。 */
    public String denyReason() {
        return denyReason;
    }

    /**
     * pre-execute 监听器声明本次调用需审批（ask 三态）。声明者不裁决，
     * 只标记"此调用需要审批"，裁决权交给审批策略服务的解析者（
     * {@link #resolveApproval(ApprovalDecision)}）。与 {@link #deny(String)}
     * 互斥：直接 deny 占先，审批段不执行。
     */
    public void requestApproval() {
        this.approvalRequested = true;
    }

    /** 是否有声明者请求了审批（工具自身声明或 pre-execute 监听器声明）。 */
    public boolean approvalRequested() {
        return approvalRequested;
    }

    /**
     * 审批策略服务解析者写入裁决结果（同时置位请求标志——裁决蕴含已请求）。
     * 请求未被解析时由管线按"未配置即拒"处理。
     */
    public void resolveApproval(ApprovalDecision decision) {
        this.approvalRequested = true;
        this.approvalDecision = decision;
    }

    /** 审批裁决结果；未被解析为 null。 */
    public ApprovalDecision approvalDecision() {
        return approvalDecision;
    }

    /**
     * execute 段写入执行结果（正常形态）。
     * 超时终局（{@link #markTimeout}）之后的迟到写入一律丢弃——模型已收到超时错误，
     * 迟到的真实结果不再采用；同步守护消除与超时监听器线程的竞态窗口。
     */
    public synchronized void setResult(Object value) {
        if (resultFrozen) {
            return;
        }
        this.result = value;
        this.resultIsError = false;
    }

    /** 结果转为错误形态（execute 抛错或 post-execute 治理调用）。 */
    public void markError(String message) {
        this.result = message;
        this.resultIsError = true;
    }

    /**
     * 超时终局：写入超时错误并冻结后续结果写入（ADR-0018）。
     * 与 {@link #markError(String)} 的差别只在冻结——普通错误路径（工具抛错、
     * post-execute 治理）保持串行时序无迟到竞争，不冻结以免剥夺 post 段修复语义。
     */
    public synchronized void markTimeout(String message) {
        this.result = message;
        this.resultIsError = true;
        this.resultFrozen = true;
    }

    /** 当前结果值。 */
    public Object result() {
        return result;
    }

    /** 结果是否为错误形态。 */
    public boolean resultIsError() {
        return resultIsError;
    }
}
