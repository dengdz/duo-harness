package dev.duo.harness.llm;

import java.util.function.Consumer;

/**
 * LLM 调用的 provider 中立契约：把一次对话请求翻译为 provider 协议调用，
 * 并以流式回调逐段交付输出。
 *
 * <p>实现（OpenAI 兼容适配器等）在 {@code llm.internal}；agent 循环（M5）
 * 只依赖本接口——provider 可替换而循环不动。M3 不做重试：调用失败以异常
 * 原样抛出（状态码与 provider 错误消息保留），由调用方呈现。</p>
 */
public interface LlmAdapter {

    /**
     * 流式执行一次对话：provider 每返回一段增量文本，就通过 {@code onChunk}
     * 回调交付一个 {@link ChatChunk}。方法返回即输出结束。
     *
     * @param request 对话请求（system 指令 + 用户消息）
     * @param onChunk 增量回调（调用方决定打印/写会话事件）
     * @throws dev.duo.harness.core.api.PluginException 调用失败（网络/协议/凭证），消息保留 provider 错误详情
     */
    void stream(ChatRequest request, Consumer<ChatChunk> onChunk);

    /**
     * 流式执行一轮 LLM 调用（agent 循环形态）：文本增量经 {@code textSink}
     * 逐段交付（REPL 打印），方法返回时交付聚合结果——完整文本与模型发起的
     * 工具调用请求列表。
     *
     * <p>与 {@link #stream} 的区别：本方法面向需要消费 tool_calls 的调用方
     * （agent 循环）；直答场景用 {@link #stream} 即可。</p>
     *
     * @param request  对话请求（含可选工具清单）
     * @param textSink 文本增量消费（流式打印）
     * @return 一轮聚合结果（完整文本 + 工具调用请求列表）
     * @throws PluginException 调用失败（网络 / 协议 / 凭证），错误原样呈现不重试
     */
    LlmTurn streamTurn(ChatRequest request, Consumer<String> textSink);
}
