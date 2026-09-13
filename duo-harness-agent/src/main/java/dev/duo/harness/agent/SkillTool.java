package dev.duo.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.PluginException;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolExecution;

import java.util.Objects;

/**
 * 技能加载工具（模型自触发路，M7 三路触发之一）：模型按名调用即返回该技能的
 * 指令全文，遵循执行。走六段管线不豁免（审批 / guard 照常可用）；
 * 未知名收敛为 error 结果点名可用技能。
 */
public final class SkillTool implements ToolDefinition {

    /** 工具名（模型侧调用名）。 */
    public static final String NAME = "skill";

    private final SkillRegistry skills;

    /** @param skills 技能注册表（启动扫描产物） */
    public SkillTool(SkillRegistry skills) {
        this.skills = Objects.requireNonNull(skills, "skills");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按名加载技能的完整指令。当任务匹配某个可用技能的描述时调用；"
                + "调用后会返回该技能的指令内容，请严格遵循执行。";
    }

    @Override
    public JsonNode parameters() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"要加载的技能名（见系统提示中的可用技能清单）"}},
                     "required":["name"]}""");
        } catch (Exception e) {
            throw new IllegalStateException("skill 工具参数 schema 内置错误", e);
        }
    }

    @Override
    public String execute(ToolExecution execution) {
        JsonNode nameNode = execution.args().get("name");
        if (nameNode == null || nameNode.isNull() || nameNode.asText().isBlank()) {
            throw new PluginException("skill 缺少必填参数 name（要加载的技能名）");
        }
        Skill skill = skills.find(nameNode.asText().strip());
        if (skill == null) {
            throw new PluginException("未知技能: " + nameNode.asText().strip()
                    + "（可用: " + skills.all().stream().map(Skill::name)
                    .reduce((a, b) -> a + ", " + b).orElse("无") + "）");
        }
        return skill.content() + "\n\n（请遵循以上技能指令完成用户任务。）";
    }
}
