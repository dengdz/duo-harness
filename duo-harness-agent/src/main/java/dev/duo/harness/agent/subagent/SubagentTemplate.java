package dev.duo.harness.agent.subagent;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 子代理模板（ADR-0015 决策 5）：部署者预定义的子 agent 能力边界——工具清单 +
 * 可选专属提示 + 可选迭代上限。spawn/fork 时模型只点名模板，工具配置权在部署者
 * （M12 权限预设同一治理哲学），模型没有配工具的权力。
 *
 * @param name          模板名（spawn/fork 的点名键，配置内唯一）
 * @param tools         工具名清单（按声明顺序生效；可用集在装配处再经强制过滤收窄）
 * @param prompt        专属提示（追加进子 agent 提示；null 即无专属提示）
 * @param maxIterations 迭代上限（null = {@value #DEFAULT_MAX_ITERATIONS}）——子代理
 *                      承担的是被委派的重活（多文件读取、批量检索、汇总成文），轮次
 *                      需求普遍高于主对话；配置错误（非正）在解析期点名
 */
public record SubagentTemplate(String name, List<String> tools, String prompt, Integer maxIterations) {

    /**
     * 默认迭代上限：高于主 agent 的 {@code ToolCallingAgent.MAX_ITERATIONS}（10）——
     * 子代理跑的是父 agent 交出来的收集/执行型子任务，实测一轮"读多模块 pom + 统计
     * 全仓包结构"即可用掉 27 次工具调用，10 轮会在收尾前被掐断。
     */
    public static final int DEFAULT_MAX_ITERATIONS = 30;

    /**
     * 强制排除集（不可绕过）：交互工具（ask_user / exit_plan_mode——子代理不得
     * 绕过部署者直接问人或自作主张退出计划）与控制面五件（治理权归父；spawn/fork
     * 不进子模板即禁嵌套，深度恒为 1，ADR-0015 决策 6，无深度配额守卫）。
     */
    public static final Set<String> FORBIDDEN_TOOLS = Set.of(
            "ask_user", "exit_plan_mode",
            "spawn", "fork", "send_message", "interrupt_agent", "list_agents");

    /** 构造时校验非空与防御性拷贝——错误前移到构造点。 */
    public SubagentTemplate {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(tools, "tools");
        if (name.isBlank()) {
            throw new IllegalArgumentException("模板名不能为空");
        }
        if (maxIterations != null && maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations 必须为正: " + maxIterations);
        }
        tools = List.copyOf(tools);
    }

    /** 便捷构造：无专属提示、迭代上限取默认。 */
    public SubagentTemplate(String name, List<String> tools) {
        this(name, tools, null, null);
    }

    /** 生效迭代上限：配置值优先，未配走 {@link #DEFAULT_MAX_ITERATIONS}。 */
    public int effectiveMaxIterations() {
        return maxIterations == null ? DEFAULT_MAX_ITERATIONS : maxIterations;
    }

    /**
     * 子 agent 可用工具集：模板清单 ∩ 在册注册表 − 强制排除集（保持声明顺序）。
     * 模板配了禁用工具也被滤除——治理底线是过滤而非报错，部署者误配不放大为
     * 子代理越权；模板引用未注册工具自然不进集（是否点名属 spawn 期语义）。
     */
    public List<String> allowedTools(Set<String> registered) {
        List<String> allowed = new java.util.ArrayList<>();
        for (String tool : tools) {
            if (registered.contains(tool) && !FORBIDDEN_TOOLS.contains(tool) && !allowed.contains(tool)) {
                allowed.add(tool);
            }
        }
        return List.copyOf(allowed);
    }
}
