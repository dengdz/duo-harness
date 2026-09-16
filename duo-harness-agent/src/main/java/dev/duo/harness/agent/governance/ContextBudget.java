package dev.duo.harness.agent.governance;

/**
 * 上下文预算计量（M9 治理基座）：本地字符估算——字符数 ÷ 估算系数（每 token 约
 * 4 字符，取整上浮）。provider 无关、零依赖、无 IO：粗粒度触发（如"超阈值压缩"）
 * 宁可早不可晚，精确计量留待 provider usage 捕获（增强项，非本期）。
 */
public final class ContextBudget {

    /** 每 token 估算字符数（中英混合语料偏保守：4 字符大概率高估 token，触发宁早）。 */
    public static final int CHARS_PER_TOKEN = 4;

    private ContextBudget() {
    }

    /** 单段文本的 token 估算（null 视为空；上取整——触发宁早）。 */
    public static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /** 消息列表的 token 估算（全部 content 求和；toolCalls 参数文本计入 assistant 载荷）。 */
    public static long estimateMessageTokens(Iterable<dev.duo.harness.session.Message> messages) {
        long total = 0;
        for (dev.duo.harness.session.Message message : messages) {
            total += estimateTokens(message.content());
            if (message.toolCalls() != null) {
                for (dev.duo.harness.session.ToolCall call : message.toolCalls()) {
                    total += estimateTokens(call.argumentsJson());
                }
            }
        }
        return total;
    }
}
