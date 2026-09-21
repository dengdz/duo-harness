package dev.duo.harness.agent;

import dev.duo.harness.session.Session;

/**
 * agent 对话服务：一次任务的编排入口——会话记录、LLM 流式调用、工具执行
 * 与结果回填的循环，直至模型给出最终回答。
 *
 * <p>实现（工具循环）在 {@code agent.internal}；消费方（REPL / 未来 Web）
 * 依赖本契约。构造与装配参数见实现类。</p>
 */
public interface ChatAgent {

    /** 呈现位标记：终端（CLI 插件构造 agent 时注入，随工具执行携带）。 */
    String PRESENTER_CLI = "cli";

    /** 呈现位标记：浏览器（Web 插件构造 agent 时注入，随工具执行携带）。 */
    String PRESENTER_WEB = "web";

    /**
     * 执行一次 agent 任务：用户输入入会话 → 循环（LLM 调用 ↔ 工具执行）
     * → 过程经 {@code listener} 通知 → 返回最终结果。
     *
     * <p>副作用：用户输入在 LLM 调用前已写入会话日志——LLM 调用失败时
     * 会话留下悬空的 user 消息（无对应回复），调用方呈现错误后可继续
     * 同一会话重试。</p>
     *
     * @param userText 用户输入
     * @param listener 过程回调（流式增量 / 工具调用通知；不可为 null）
     * @return 任务最终结果（最终回复 + 工具调用记录 + 是否正常完成）
     * @throws PluginException LLM 调用失败（网络 / 协议 / 凭证），错误原样呈现不重试
     */
    AgentReply send(String userText, AgentListener listener);

    /**
     * 运行中消息注入（两级收件箱的 next-step 级，M19 ADR-0020 决策 8 立、M23
     * ADR-0025 决策一升级）：agent 执行（busy）期间收到的用户消息进 next-step
     * 级收件箱，send 循环在 step 边界（下一轮请求构造前）排干为普通 user/message
     * ——模型下一步即可见，不打断飞行中的工具组（与子代理 send_message 的
     * "下一轮生效"语义对称）。
     *
     * @param text 用户消息文本
     * @return true = 已接收（将随下一次 step 边界进入会话与请求）；false = 本实现
     *         不支持运行中注入（调用方回退既有语义，如 Web 409）
     */
    default boolean injectUserMessage(String text) {
        return false;
    }

    /**
     * next-turn 级注入（M23 ADR-0025 决策一）：不进当前 turn——执行中注入不落
     * 日志、不进后续请求，turn 收口后由呈现位经 {@link #drainNextTurn()} 取走
     * （生效 = 开新轮，如后台完成通知）。空闲期注入同样只入队，不自动触发——
     * idle 时的立即唤醒开轮属后台通知路由（M23 工单 04）。
     *
     * @param text 排队文本
     * @return true = 已入队；false = 本实现不支持或文本空白
     */
    default boolean injectNextTurn(String text) {
        return false;
    }

    /**
     * turn 收口排干：取走全部 next-turn 排队文本（先进先出，多次调用依次取空）。
     * 呈现位在 send 返回后调用；多条由呈现位合并为一条生效（ADR-0025 决策二）。
     *
     * @return 排队文本（可能为空；实现不支持时恒空）
     */
    default java.util.List<String> drainNextTurn() {
        return java.util.List.of();
    }
}
