package dev.duo.harness.session;

import java.util.Objects;

/**
 * 会话事件：追加式日志的唯一条目形态。
 *
 * <p>M4 三种事件载荷同构（type + at + text）；M5 起工具事件额外携带
 * {@code toolCallId} / {@code toolName}（协议要求 tool 结果消息与模型发起的
 * tool_calls 按 id 关联）；M6 起工具调用事件额外携带 {@code reasoning}
 * （思考模式 provider 要求回传，随事件持久化——会话投影重建完整请求历史）。
 * JSONL 按 {@code type} 字符串判别，可选字段缺省不破坏旧会话文件（追加写入
 * 向后兼容）。</p>
 *
 * @param type       事件类型（见本类常量）
 * @param at         事件时间戳（epoch millis）
 * @param text       事件载荷文本（tool/call 为参数 JSON；tool/result 为结果文本；
 *                   command/run 为命令参数文本；command/done 为命令结果文本）
 * @param toolCallId 协议关联 id（工具事件与子代理事件携带——后者为子 agent id，其余为 null）
 * @param toolName   工具名（工具事件与子代理 spawned 携带——后者为模板名；command 两事件
 *                   携带命令名；其余为 null）
 * @param reasoning  思考内容（仅 tool/call 携带，其余为 null）
 * @param usage      真实 token 用量（仅 assistant/message 携带，provider 未报告为 null）
 */
public record SessionEvent(String type, long at, String text, String toolCallId, String toolName,
                           String reasoning, TokenUsage usage) {

    /** 用户消息（每轮用户输入的完整文本）。 */
    public static final String USER_MESSAGE = "user/message";

    /** 助手流式增量（LLM 每段输出；投影时不入消息列表）。 */
    public static final String ASSISTANT_CHUNK = "assistant/chunk";

    /** 助手完整消息（全部 chunk 拼接后的最终文本）。 */
    public static final String ASSISTANT_MESSAGE = "assistant/message";

    /** 工具调用开始（text = 参数 JSON；toolCallId/toolName 携带关联信息，reasoning 携带思考内容）。 */
    public static final String TOOL_CALL = "tool/call";

    /** 工具调用结果（text = 结果文本；失败为错误说明）。 */
    public static final String TOOL_RESULT = "tool/result";

    /** 审批请求（M6 交互事件；text = 参数摘要，toolName = 工具名。投影时跳过——审计事件不进对话消息）。 */
    public static final String APPROVAL_REQUESTED = "approval/requested";

    /** 审批决定（M6 交互事件；text = 决定与来源，toolName = 工具名。投影时跳过）。 */
    public static final String APPROVAL_DECIDED = "approval/decided";

    /** 运行错误（M8；text = 错误消息。直推帧不落会话——projection 跳过；类型保留供 SSE 通道复用）。 */
    public static final String RUN_ERROR = "run/error";

    /** 会话标题（M13；text = 标题文本。投影 latest-wins 经 {@code Session.title()} 读取，不进对话消息）。 */
    public static final String TITLE = "session/title";

    /**
     * todo 清单写入（M17；text = todos 数组 JSON——每项 content + status，会话层透明往返）。
     * 投影 latest-wins 经 {@code Session.todoProjection()} 读取（新 user/message 清空、终版回复后保留），
     * 不进对话消息——清单是呈现状态不是对话内容（ADR-0018）。
     */
    public static final String TODO_WRITE = "todo/write";

    /**
     * 子代理已派生（M15；text = 载荷 JSON——任务描述、模式、fork 源引用，
     * toolCallId = 子 agent id，toolName = 模板名。投影跳过——呈现卡片专用）。
     */
    public static final String SUBAGENT_SPAWNED = "subagent/spawned";

    /**
     * 子代理完成（M15；text = 结果概要与最终回答，toolCallId = 子 agent id。
     * 最终回答投影进父 LLM 上下文——父聚合结果的数据源）。
     */
    public static final String SUBAGENT_COMPLETED = "subagent/completed";

    /**
     * 子会话种子边界（M15 fork 播种；text = "前 N 条来自父会话 <id>" 的标记，
     * 写在播种段之后、子任务消息之前）。真用户消息与播种消息据此在审计上可区分
     * （ADR-0015 决策 4）；投影跳过——边界是审计标记，不是对话消息。
     */
    public static final String SUBAGENT_SEED_BOUNDARY = "subagent/seed-boundary";

    /**
     * 子代理中止痕迹（M15 控制面 interrupt；text = 中止说明，toolCallId = 子 agent id，
     * 落子会话日志）。父侧终局经 {@code subagent/completed} 回流（text 标注"已被中止"）
     * ——投影层只认 completed 一种终局事件；本事件是子会话侧的可审计终止痕迹。
     */
    public static final String SUBAGENT_INTERRUPTED = "subagent/interrupted";

    /**
     * 斜杠命令执行开始（M19，ADR-0020 决策 5；text = 命令参数文本，toolName = 命令名）。
     * 与 command/done 成对（先 run 后 done）——崩溃断口可观测；投影排除（命令操作
     * harness 不进模型历史），Web 命令行渲染与 CLI 回显的消费源。
     */
    public static final String COMMAND_RUN = "command/run";

    /**
     * 斜杠命令执行完成（M19，ADR-0020 决策 5；text = 结果文本，toolName = 命令名）。
     * 命令异常收敛为错误说明文本照常落 done——审计面只见结果，不见异常通道。
     */
    public static final String COMMAND_DONE = "command/done";

    /**
     * 权限档切换（M19，ADR-0020 决策 10；text = 档位 configName，如 "read-only"）。
     * /permission 切档成功时落盘；latest-wins 经 {@code Session.permissionMode()} 读取
     * ——会话重开恢复最后档位（档位跟对话走），新会话无事件即回 yml 缺省。
     */
    public static final String PERMISSION_MODE = "permission/mode";

    /**
     * 上下文压缩点（M19，ADR-0020 决策 6；text = 远端历史的总结全文，toolName = 触发方式
     * {@code "manual"}（/compact 命令）或 {@code "auto"}（预算触发））。一处语义两处触发
     * ——"上下文为何变小"在日志可审计。投影 latest-wins：最后压缩点之前的一切以总结
     * 替换、之后照常；刷新/重开经日志重放天然恢复压缩态、不重复总结。
     */
    public static final String COMPACTION = "context/compacted";

    /**
     * 压缩替换头（M19）：治理管线折叠与投影压缩点共用的首行说明——单一事实来源保证
     * 两侧逐字同文（任何一侧单独改动即静默漂移的防线，OCR #13/#16）。
     */
    public static final String COMPACTION_SUMMARY_HEADER =
            "[以下是本会话早期历史的压缩摘要，原文已归档在会话日志中]\n\n";

    /** 构造时校验非空——错误前移到构造点。 */
    public SessionEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        if (at < 0) {
            throw new IllegalArgumentException("at 不能为负: " + at);
        }
    }

    /** 兼容构造：非工具事件（无关联信息）。 */
    public SessionEvent(String type, long at, String text) {
        this(type, at, text, null, null, null, null);
    }

    /** 兼容构造：工具事件（无思考内容）。 */
    public SessionEvent(String type, long at, String text, String toolCallId, String toolName) {
        this(type, at, text, toolCallId, toolName, null, null);
    }

    /** 兼容构造：工具事件 + 思考内容。 */
    public SessionEvent(String type, long at, String text, String toolCallId, String toolName,
                        String reasoning) {
        this(type, at, text, toolCallId, toolName, reasoning, null);
    }

    /** 便捷工厂：当前时刻的用户消息。 */
    public static SessionEvent userMessage(String text) {
        return new SessionEvent(USER_MESSAGE, System.currentTimeMillis(), text);
    }

    /** 便捷工厂：当前时刻的助手流式增量。 */
    public static SessionEvent assistantChunk(String text) {
        return new SessionEvent(ASSISTANT_CHUNK, System.currentTimeMillis(), text);
    }

    /** 便捷工厂：当前时刻的助手完整消息（无用量——provider 未报告或非 agent 链路）。 */
    public static SessionEvent assistantMessage(String text) {
        return assistantMessage(text, null);
    }

    /** 便捷工厂：助手完整消息 + 真实 token 用量（provider 报告时随事件持久化，ADR-0009）。 */
    public static SessionEvent assistantMessage(String text, TokenUsage usage) {
        return new SessionEvent(ASSISTANT_MESSAGE, System.currentTimeMillis(), text, null, null, null, usage);
    }

    /** 便捷工厂：工具调用开始（id 关联模型发起的调用；无思考内容）。 */
    public static SessionEvent toolCall(String toolCallId, String toolName, String argsJson) {
        return toolCall(toolCallId, toolName, argsJson, null);
    }

    /** 便捷工厂：工具调用开始 + 思考内容（思考模式 provider 要求回传，随事件持久化）。 */
    public static SessionEvent toolCall(String toolCallId, String toolName, String argsJson, String reasoning) {
        return new SessionEvent(TOOL_CALL, System.currentTimeMillis(), argsJson, toolCallId, toolName, reasoning);
    }

    /** 便捷工厂：工具调用结果（id 关联模型发起的调用）。 */
    public static SessionEvent toolResult(String toolCallId, String toolName, String resultText) {
        return new SessionEvent(TOOL_RESULT, System.currentTimeMillis(), resultText, toolCallId, toolName);
    }

    /** 便捷工厂：审批请求（工具调用被声明需审批、交由回答者作答前）。 */
    public static SessionEvent approvalRequested(String toolName, String detail) {
        return new SessionEvent(APPROVAL_REQUESTED, System.currentTimeMillis(), detail, null, toolName, null);
    }

    /** 便捷工厂：运行错误（SSE 直推帧用；不 append 进会话）。 */
    public static SessionEvent errorEvent(String text) {
        return new SessionEvent(RUN_ERROR, System.currentTimeMillis(), text);
    }

    /** 便捷工厂：审批决定（text = 决定与回答者来源，如 "allow（回答者: console）"）。 */
    public static SessionEvent approvalDecided(String toolName, String decisionText) {
        return new SessionEvent(APPROVAL_DECIDED, System.currentTimeMillis(), decisionText, null, toolName, null);
    }

    /** 便捷工厂：会话标题（生成器一次写入；重写即投影 latest-wins 自然覆盖）。 */
    public static SessionEvent title(String text) {
        return new SessionEvent(TITLE, System.currentTimeMillis(), text);
    }

    /** 便捷工厂：todo 清单写入（整表替换语义；todosJson 为规范化后的清单数组 JSON）。 */
    public static SessionEvent todoWrite(String todosJson) {
        return new SessionEvent(TODO_WRITE, System.currentTimeMillis(), todosJson);
    }

    /**
     * 便捷工厂：子代理已派生（id 关联后续 completed；模板名走工具名可选位，
     * 载荷 JSON 走 text——任务描述、模式、fork 源引用由写入方组装，会话层透明往返）。
     */
    public static SessionEvent subagentSpawned(String agentId, String templateName, String payloadJson) {
        return new SessionEvent(SUBAGENT_SPAWNED, System.currentTimeMillis(), payloadJson, agentId, templateName, null);
    }

    /** 便捷工厂：子代理完成（id 关联 spawned；text = 结果概要与最终回答）。 */
    public static SessionEvent subagentCompleted(String agentId, String resultText) {
        return new SessionEvent(SUBAGENT_COMPLETED, System.currentTimeMillis(), resultText, agentId, null, null);
    }

    /** 便捷工厂：子会话种子边界（text = "前 N 条来自父会话 <id>" 标记）。 */
    public static SessionEvent subagentSeedBoundary(String parentSessionId, int seededCount) {
        return new SessionEvent(SUBAGENT_SEED_BOUNDARY, System.currentTimeMillis(),
                "前 " + seededCount + " 条来自父会话 " + parentSessionId);
    }

    /** 便捷工厂：子代理中止痕迹（id 关联 spawned；落子会话，父侧终局走 completed）。 */
    public static SessionEvent subagentInterrupted(String agentId, String reason) {
        return new SessionEvent(SUBAGENT_INTERRUPTED, System.currentTimeMillis(), reason, agentId, null, null);
    }

    /** 便捷工厂：斜杠命令执行开始（名 + 参数文本；args 可为空串）。 */
    public static SessionEvent commandRun(String name, String args) {
        return new SessionEvent(COMMAND_RUN, System.currentTimeMillis(), args, null, name, null);
    }

    /** 便捷工厂：斜杠命令执行完成（名 + 结果文本；异常已收敛为错误说明文本）。 */
    public static SessionEvent commandDone(String name, String result) {
        return new SessionEvent(COMMAND_DONE, System.currentTimeMillis(), result, null, name, null);
    }

    /** 便捷工厂：上下文压缩点（总结全文 + 触发方式 manual/auto 走工具名可选位）。 */
    public static SessionEvent compaction(String summary, String trigger) {
        return new SessionEvent(COMPACTION, System.currentTimeMillis(), summary, null, trigger, null);
    }

    /** 便捷工厂：权限档切换（档位 configName；latest-wins 投影）。 */
    public static SessionEvent permissionMode(String configName) {
        return new SessionEvent(PERMISSION_MODE, System.currentTimeMillis(), configName);
    }
}
