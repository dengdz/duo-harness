package dev.duo.harness.agent;

import java.util.Objects;

/**
 * 技能：一份可被模型按需加载的流程能力包（M7）。
 *
 * @param name        技能名（发现根内唯一，模型按名调用）
 * @param description 描述（注入清单供模型判断何时加载）
 * @param content     指令全文（skill 工具的结果内容，模型遵循执行）
 */
public record Skill(String name, String description, String content) {

    /** 构造时校验非空——错误前移到构造点。 */
    public Skill {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(content, "content");
    }
}
