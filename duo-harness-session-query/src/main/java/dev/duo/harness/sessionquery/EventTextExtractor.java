package dev.duo.harness.sessionquery;

import dev.duo.harness.session.SessionEvent;

/**
 * 索引内容抽取（ADR-0022 决策 8，DSH 清单对齐）：决定一个会话事件哪些文本
 * 进入检索索引。白名单语义——清单内的类型入索引，其余一律不入：
 * <ul>
 *   <li>入：user/message、assistant/message 文本；tool/call 工具名+参数；
 *       tool/result 结果；todo/write 清单项内容；run/error（turn 错误，
 *       当前版本不落盘但类型保留——夹具用例钉住其入索引语义）；</li>
 *   <li>入（消息语义）：subagent/completed——子代理最终回答投影为父上下文
 *       消息（Session.projectsToMessage 同判），属真实对话文本；</li>
 *   <li>不入：assistant/chunk（与完整消息重复的过程细节）、reasoning
 *       （物理不读该字段——{@link IndexedEvent} 载荷里根本没有它）、
 *       title/审批/命令/权限档/压缩点/附件引用块等治理与元数据事件。</li>
 * </ul>
 */
public final class EventTextExtractor {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private EventTextExtractor() { }

    /**
     * 事件的可检索文本（null = 不入索引）。tool/call 返回"工具名 + 参数 JSON"
     * 拼接——两类词都可搜（按名找调用、按参数找操作）。
     */
    public static String searchableText(IndexedEvent event) {
        return switch (event.type()) {
            case SessionEvent.USER_MESSAGE, SessionEvent.ASSISTANT_MESSAGE,
                    SessionEvent.SUBAGENT_COMPLETED, SessionEvent.RUN_ERROR -> event.text();
            case SessionEvent.TOOL_CALL -> event.toolName() == null
                    ? event.text() : event.toolName() + "\n" + event.text();
            case SessionEvent.TOOL_RESULT -> event.text();
            case SessionEvent.TODO_WRITE -> todoContent(event.text());
            default -> null;
        };
    }

    /**
     * todo 清单的内容项抽取：todos 数组每项的 content 值换行拼接（状态字段
     * 不入——"completed" 这类词命中清单是噪音）；解析失败回退原文（会话层
     * 对 todo JSON 是透明往返，坏形态按原文搜）。
     */
    private static String todoContent(String todosJson) {
        try {
            var node = MAPPER.readTree(todosJson);
            if (!node.isArray()) {
                return todosJson;
            }
            StringBuilder out = new StringBuilder();
            for (var item : node) {
                String content = item.path("content").asText("");
                if (!content.isBlank()) {
                    out.append(content).append('\n');
                }
            }
            return out.isEmpty() ? null : out.toString().strip();
        } catch (Exception e) {
            return todosJson;
        }
    }
}
