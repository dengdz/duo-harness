package dev.duo.harness.tools;

/**
 * 回答者：注册进交互服务的呈现端实现（ADR-0008）。
 *
 * <p>接收交互请求（审批 / 提问），呈现给人并返回回答。返回 {@code null} 表示
 * 放弃作答权、交给下一个在场回答者（注册序遍历）；全部放弃或无人在场时
 * fail-closed。实现随注册作用域自动摘除（M8 的 Web answerer 是第二个实现位）。</p>
 *
 * <p>命名约定：实现应以来源标识署名（如 {@code console}），审计与错误呈现可见。</p>
 */
public interface Answerer {

    /**
     * 作答一次交互请求。
     *
     * @param request 交互请求（审批或提问）
     * @return 回答；{@code null} = 放弃作答权，交给下一个回答者
     */
    InteractionAnswer answer(InteractionRequest request);
}
