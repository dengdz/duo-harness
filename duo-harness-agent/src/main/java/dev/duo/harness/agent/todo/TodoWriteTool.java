package dev.duo.harness.agent.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.duo.harness.session.Session;
import dev.duo.harness.session.SessionEvent;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * todo_write 工具（ADR-0018，DSH 同款词汇）：agent 轮内任务分解抓手——
 * 模型一次传入完整清单，整表替换上一次的写入（无部分更新、无逐项编辑）。
 *
 * <p>数据三通道各司其职：{@code todo/write} 事件落会话日志（latest-wins 投影、
 * 重开会话恢复，呈现面经 {@link Session#todoProjection()} 读取）；模型只收一句
 * 计数回显（完整清单不作为第二条消息回流——模型对清单的记忆来自它自己调用
 * 参数里的整表）；分解纪律全在本工具 description（多步先拆、平凡不拆、全量
 * 发送、完成即刻标记）。校验：content trim 后非空且不重复；多任务同时
 * in_progress 不校验（并行执行时代的常态，纪律靠引导）。</p>
 *
 * <p>todo_write 是独占工具（不覆写 isConcurrencySafe，fail-closed 默认）——
 * 清单是共享呈现状态，并行写入无意义。</p>
 */
public final class TodoWriteTool implements ToolDefinition {

    /** 工具名（模型侧调用名）。 */
    public static final String NAME = "todo_write";

    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed");

    private final Supplier<Session> currentSession;

    /** @param currentSession 当前会话供给（换绑后留新会话，与交互工具同模式） */
    public TodoWriteTool(Supplier<Session> currentSession) {
        this.currentSession = Objects.requireNonNull(currentSession, "currentSession");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "维护本轮任务的执行清单。每次调用发送完整清单——它整体替换上一次的清单"
                + "（没有部分更新，也没有逐项编辑）。多步任务开工前先拆清单（每个具体步骤一条"
                + "祈使句）；平凡的单步任务不必使用。开工即把该步标记为 in_progress（并行执行"
                + "多个任务时可有多项同时进行中）；每完成一步就立即重新调用本工具、把该步标记"
                + "为 completed，不要攒到最后一次性批量更新。";
    }

    @Override
    public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {"type":"object","properties":{
                      "todos":{"type":"array","description":"完整任务清单（整体替换上一次的清单）",
                        "items":{"type":"object","additionalProperties":false,
                          "properties":{
                            "content":{"type":"string","description":"任务内容——一句简短祈使句"},
                            "status":{"type":"string","enum":["pending","in_progress","completed"],
                                      "description":"pending 未开始 | in_progress 进行中 | completed 已完成"}},
                          "required":["content","status"]}}},
                      "required":["todos"]}""");
        } catch (Exception e) {
            throw new IllegalStateException("todo_write 参数 schema 内置错误", e);
        }
    }

    @Override
    public String execute(ToolExecution exec) {
        Session session = currentSession.get();
        if (session == null) {
            return error("todo_write 需要一个归属会话（当前无活跃会话）");
        }
        JsonNode todos = exec.args().path("todos");
        if (!todos.isArray() || todos.isEmpty()) {
            return error("参数 todos 必须是非空数组（完整清单整体替换）");
        }

        // 校验 + 规范化：content trim 后非空、不重复；status 枚举；规范化形态落日志
        ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
        Set<String> seenContents = new HashSet<>();
        int pending = 0;
        int inProgress = 0;
        int completed = 0;
        for (JsonNode item : todos) {
            String content = item.path("content").asText("").trim();
            if (content.isEmpty()) {
                return error("清单项 content 不能为空（trim 后仍为空）");
            }
            if (!seenContents.add(content)) {
                return error("清单项 content 重复（整表以内容为键，重复即歧义）: " + content);
            }
            String status = item.path("status").asText("");
            if (!STATUSES.contains(status)) {
                return error("清单项 status 必须是 pending / in_progress / completed 之一: " + status);
            }
            switch (status) {
                case "pending" -> pending++;
                case "in_progress" -> inProgress++;
                default -> completed++;
            }
            normalized.addObject().put("content", content).put("status", status);
        }

        session.append(SessionEvent.todoWrite(normalized.toString()));
        return "清单已更新：" + pending + " 待办 / " + inProgress + " 进行中 / " + completed + " 已完成。";
    }

    private static String error(String msg) {
        return "[todo_write 错误] " + msg;
    }
}
