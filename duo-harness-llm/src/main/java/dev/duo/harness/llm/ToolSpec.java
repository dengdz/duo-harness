package dev.duo.harness.llm;

import java.util.Objects;

/**
 * 工具描述的 provider 中立形态：发给 LLM 的工具清单条目。
 *
 * <p>由消费方（agent 循环）从工具域的 ToolDefinition 转换而来——llm 与
 * 工具域零耦合（M4 消息类型分属先例）。{@code parametersJson} 为参数的
 * JSON Schema 文本（协议原样透传）。</p>
 *
 * @param name           工具名
 * @param description    工具描述（模型可见）
 * @param parametersJson 参数 JSON Schema 文本
 */
public record ToolSpec(String name, String description, String parametersJson) {

    /** 构造时校验非空——错误前移到构造点。 */
    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parametersJson, "parametersJson");
    }
}
