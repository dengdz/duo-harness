package dev.duo.harness.agent.subagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.session.Session;
import dev.duo.harness.agent.subagent.SubagentManager;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * spawn 工具（ADR-0015 决策 2）：父 agent 把子任务交给全新子代理——立即返回
 * agent id（后台异步模型），子代理用模板预审定的工具集在后台虚拟线程运行；
 * 完成后最终回答经 {@code subagent/completed} 自动回流父上下文。工具配置权在
 * 部署者（模板制），本工具只收模板名与任务描述——无配工具参数。
 */
public final class SpawnTool implements ToolDefinition {

    /** 工具名（模型侧调用名，DSH 同款词汇）。 */
    public static final String NAME = "spawn";

    private final SubagentManager manager;
    private final Supplier<Session> currentSession;

    public SpawnTool(SubagentManager manager, Supplier<Session> currentSession) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.currentSession = Objects.requireNonNull(currentSession, "currentSession");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "派生一个全新子代理在后台执行独立子任务（立即返回 agent id，不等待完成）。"
                + "子代理看不到本对话——它只拿到你给的 task，干完把结论交回来，没有跨任务的记忆。"
                + "因此：① task 必须自包含（写明目标、范围、期望产出，不依赖'我们刚说的'这类指代）；"
                + "② task 应是边界清晰、有限步内可完成的一件小事（如'读这几个文件并总结'、"
                + "『统计某目录下的类分布』）；需要数十步采集的大任务，请拆成多个子代理分别派发，"
                + "或先自己探索定位再委派小块；③ 子代理有迭代上限，任务过大它会在中途耗尽轮次。"
                + "适合过程冗长、结论可比过程更短的工作（探索、批量处理、独立调研）——"
                + "中间过程不占用主对话上下文。template 必须从已配置的模板中点名。"
                + "用 list_agents 查看各子代理进展，用 send_message 补充指示。"
                + "可用模板：" + String.join("、", manager.templateNames()) + "。";
    }

    @Override
    public JsonNode parameters() {
        try {
            var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                    .put("type", "object");
            var props = schema.putObject("properties");
            var template = props.putObject("template")
                    .put("description", "子代理模板名（部署方预定义的能力边界）");
            var enumNames = template.putArray("enum");
            manager.templateNames().forEach(enumNames::add);
            props.putObject("task")
                    .put("description", "子任务描述：目标、约束、期望产出，自包含");
            schema.putArray("required").add("template").add("task");
            return schema;
        } catch (Exception e) {
            throw new IllegalStateException("spawn 参数 schema 内置错误", e);
        }
    }

    @Override
    public Object execute(ToolExecution execution) {
        String template = requireText(execution.args(), "template");
        String task = requireText(execution.args(), "task");
        Session parent = currentSession.get();
        if (parent == null) {
            throw new PluginException("spawn 无可用父会话（装配不完整）");
        }
        String agentId = manager.spawn(parent, template, task);
        return "{\"agentId\":\"" + agentId + "\",\"template\":\"" + template + "\",\"status\":\"started\"}";
    }

    /** 必填文本参数严格读取：缺失或空白点名（模型可见错误后自行补参重调）。 */
    private static String requireText(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new PluginException(NAME + " 缺少必填参数 " + field);
        }
        return node.asText();
    }
}
