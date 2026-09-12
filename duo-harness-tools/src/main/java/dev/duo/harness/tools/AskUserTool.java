package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.PluginException;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 提问工具（ask_user）：模型发起的交互——执行本体即"经交互 seam 等人作答"，
 * 回答文本作为工具结果回填，模型据此继续（ADR-0008）。
 *
 * <p>参数（最小 schema，先窄后宽）：{@code question} 必填；{@code options}
 * 可选字符串数组（省略 = 自由文本回答）；{@code multiSelect} 默认 false。
 * 工具经装配挂载进工具域，走六段管线不豁免；无人应答按 fail-closed 收敛为
 * error 结果（模型可见原因）。</p>
 */
public final class AskUserTool implements ToolDefinition {

    /** 工具名（模型侧调用名）。 */
    public static final String NAME = "ask_user";

    private final InteractionService answers;

    /** @param answers 交互服务（回答者注册表；缺回答者时 fail-closed） */
    public AskUserTool(InteractionService answers) {
        this.answers = Objects.requireNonNull(answers, "answers");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "向用户提问并等待回答。当任务信息不足、存在多种做法需要用户拍板时调用；"
                + "给出具体明确的问题，可用 options 提供预设选项（用户也可自由输入）。调用会阻塞直到用户回答。";
    }

    @Override
    public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {"type":"object","properties":{
                      "question":{"type":"string","description":"要问用户的问题，一句话，具体明确"},
                      "options":{"type":"array","items":{"type":"string"},
                                 "description":"预设选项；省略表示接受自由文本回答"},
                      "multiSelect":{"type":"boolean","description":"是否允许多选，默认 false"}},
                     "required":["question"]}""");
        } catch (Exception e) {
            throw new IllegalStateException("ask_user 参数 schema 内置错误", e);
        }
    }

    @Override
    public String execute(ToolExecution execution) {
        JsonNode args = execution.args();
        JsonNode questionNode = args.get("question");
        if (questionNode == null || questionNode.isNull()
                || questionNode.asText().isBlank()) {
            throw new PluginException("ask_user 缺少必填参数 question（要问用户的问题）");
        }
        List<String> options = readOptions(args.get("options"));
        boolean multiSelect = args.path("multiSelect").asBoolean(false);

        InteractionAnswer answer = answers.ask(
                InteractionRequest.question(questionNode.asText(), options, multiSelect));
        if (!answer.approved() || answer.values().isEmpty()) {
            // fail-closed：无回答者 / 人未作答——收敛为 error 结果，模型可见原因后自行调整
            throw new PluginException("提问无人应答（fail-closed），用户当前不可达");
        }
        return answer.values().stream().collect(Collectors.joining("\n"));
    }

    /** 读取可选 options 数组（非数组或空即自由文本）。 */
    private static List<String> readOptions(JsonNode optionsNode) {
        if (optionsNode == null || !optionsNode.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(optionsNode.spliterator(), false)
                .map(n -> n.isTextual() ? n.asText() : n.toString())
                .toList();
    }
}
