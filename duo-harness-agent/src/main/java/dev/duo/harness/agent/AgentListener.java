package dev.duo.harness.agent;

/**
 * agent 任务的过程回调：循环中的流式增量与工具调用过程逐段通知。
 *
 * <p>全部方法有默认空实现——调用方按需覆写感兴趣的节点（如 REPL 打印
 * chunk、Web 面渲染调用过程）。</p>
 */
public interface AgentListener {

    /** 全空实现：不关心任何过程节点时使用。 */
    AgentListener NONE = new AgentListener() { };

    /** LLM 流式输出的一个增量片段。 */
    default void onChunk(String text) { }

    /** agent 即将执行一次工具调用（执行前通知）。 */
    default void onToolCall(String toolName, String argumentsJson) { }

    /** 工具调用完成（结果已回填给 LLM）。 */
    default void onToolResult(String toolName, String resultText, boolean isError) { }
}
