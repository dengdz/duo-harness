package dev.duo.harness.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.PluginException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 子代理模板集：config 解析单点 + 按名查寻（spawn/fork 的点名入口）。
 * 解析严格绑定（治理段解析同一先例）：未知字段、类型不符、模板名空/重复、
 * 工具清单空一律异常点名——配置错误不做静默纠正。
 *
 * <p>config 形态（{@code templates} 段缺席或为 null 即空集，部署零变化）：</p>
 * <pre>{@code templates:
 *   - name: researcher
 *     tools: [read, glob, grep]
 *     prompt: "你是调研助手"    # 可省
 *     maxIterations: 40        # 可省（缺省 30；子代理承担多步收集型重活）
 *   - name: worker
 *     tools: [read, write]}</pre>
 */
public final class SubagentTemplates {

    private final List<SubagentTemplate> templates;

    /** 构造时防御性拷贝（保序）。 */
    public SubagentTemplates(List<SubagentTemplate> templates) {
        this.templates = List.copyOf(templates);
    }

    /**
     * 解析 subagent 插件 config：{@code templates} 段缺席或为 null 返回空集
     * （未配置模板 = 零模板 = 五件工具不注册）；段在场则逐模板严格绑定。
     *
     * @throws PluginException 未知字段、类型不符、模板名空/重复、工具清单空或缺项（点名具体位置）
     */
    public static SubagentTemplates parse(JsonNode config) {
        if (config == null) {
            return new SubagentTemplates(List.of());
        }
        if (!config.isObject()) {
            throw new PluginException("subagent config 必须是对象: " + config.getNodeType());
        }
        // 顶层严格绑定：只认 templates 键——拼错键名（如 template/Templates）会让整个
        // 子代理能力静默失效（空集 → 零注册零诊断），比结构错误更难排查，故点名拒绝
        var topFields = new LinkedHashSet<String>();
        config.fieldNames().forEachRemaining(topFields::add);
        for (String field : topFields) {
            if (!"templates".equals(field)) {
                throw new PluginException("subagent config 存在未知字段: " + field
                        + "（唯一合法字段为 templates）");
            }
        }
        if (!config.hasNonNull("templates")) {
            return new SubagentTemplates(List.of());
        }
        JsonNode node = config.get("templates");
        if (!node.isArray()) {
            throw new PluginException("subagent.templates 必须是数组");
        }
        List<SubagentTemplate> parsed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (!item.isObject()) {
                throw new PluginException("subagent.templates[" + i + "] 必须是对象");
            }
            String name = null;
            List<String> tools = null;
            String prompt = null;
            Integer maxIterations = null;
            var fieldNames = new LinkedHashSet<String>();
            item.fieldNames().forEachRemaining(fieldNames::add);
            for (String field : fieldNames) {
                JsonNode value = item.get(field);
                switch (field) {
                    case "name" -> {
                        requireText("templates[" + i + "].name", value);
                        name = value.asText();
                    }
                    case "tools" -> tools = parseTools(i, value);
                    case "prompt" -> {
                        if (!value.isTextual()) {
                            throw new PluginException("subagent.templates[" + i + "].prompt 必须是文本: " + value);
                        }
                        prompt = value.asText();
                    }
                    case "maxIterations" -> {
                        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) {
                            throw new PluginException("subagent.templates[" + i
                                    + "].maxIterations 必须是正整数: " + value);
                        }
                        maxIterations = value.asInt();
                    }
                    default -> throw new PluginException("subagent.templates[" + i + "] 存在未知字段: " + field);
                }
            }
            if (name == null || name.isBlank()) {
                throw new PluginException("subagent.templates[" + i + "] 缺 name");
            }
            if (!seen.add(name)) {
                throw new PluginException("subagent.templates 模板名重复: " + name);
            }
            if (tools == null || tools.isEmpty()) {
                throw new PluginException("subagent.templates[" + i + "]（" + name + "）缺 tools 或清单为空");
            }
            parsed.add(new SubagentTemplate(name, tools, prompt, maxIterations));
        }
        return new SubagentTemplates(parsed);
    }

    /** 模板名查寻（spawn/fork 点名入口；未知名返回 empty——是否报错属调用方语义）。 */
    public Optional<SubagentTemplate> byName(String name) {
        return templates.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    /** 全部模板（配置声明顺序）。 */
    public List<SubagentTemplate> all() {
        return templates;
    }

    /** 是否为空集（未配置模板 → 插件零副作用）。 */
    public boolean isEmpty() {
        return templates.isEmpty();
    }

    /** 工具清单严格绑定：非空文本数组，逐项点名。 */
    private static List<String> parseTools(int index, JsonNode value) {
        if (!value.isArray() || value.isEmpty()) {
            throw new PluginException("subagent.templates[" + index + "].tools 必须是非空数组");
        }
        List<String> tools = new ArrayList<>();
        for (int j = 0; j < value.size(); j++) {
            JsonNode item = value.get(j);
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new PluginException("subagent.templates[" + index + "].tools[" + j + "] 必须是非空文本: " + item);
            }
            tools.add(item.asText());
        }
        return List.copyOf(tools);
    }

    private static void requireText(String where, JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new PluginException("subagent." + where + " 必须是非空文本: " + value);
        }
    }
}
