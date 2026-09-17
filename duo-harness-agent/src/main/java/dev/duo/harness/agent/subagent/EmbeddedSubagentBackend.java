package dev.duo.harness.agent.subagent;

import dev.duo.harness.agent.AgentListener;
import dev.duo.harness.agent.ToolInvocation;
import dev.duo.harness.agent.internal.ToolCallingAgent;
import dev.duo.harness.agent.governance.ContextGovernance;
import dev.duo.harness.agent.prompt.PromptRegistry;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.tools.ToolDefinition;
import dev.duo.harness.tools.ToolsService;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 同进程内嵌后端（ADR-0015 决策 1 的唯一实现）：子 agent 复用父的 LLM adapter
 * 与治理配置（fork 大前缀必需 compaction/spill），工具经过滤视图只见模板可用集，
 * system 提示 = 框架基线 + 模板专属提示（通用纪律归框架，模板只写角色；任务描述由父现场生成，不继承父的提示片段）。
 *
 * <p>治理为子任务新建实例——{@link ContextGovernance} 非线程安全（随 agent 循环
 * 串行使用），子代理在后台线程运行，与父循环并发时不得共享。</p>
 */
public final class EmbeddedSubagentBackend implements SubagentBackend {

    private final LlmAdapter llm;
    private final ToolsService sharedTools;
    private final ContextGovernance.Tuning tuning;

    /**
     * @param llm         父的 LLM adapter（子唯一从父继承的构件）
     * @param sharedTools 共享工具域注册表（多 agent 并行复用，注册表并发安全已有）
     * @param tuning      治理阈值（与父装配同源；null = 缺省常量治理，与父一致）
     */
    public EmbeddedSubagentBackend(LlmAdapter llm, ToolsService sharedTools,
                                   ContextGovernance.Tuning tuning) {
        this.llm = llm;
        this.sharedTools = sharedTools;
        this.tuning = tuning;
    }

    @Override
    public Outcome run(Task task) throws Exception {
        Set<String> registered = sharedTools.list().stream()
                .map(ToolDefinition::name).collect(Collectors.toSet());
        SubagentToolView tools = new SubagentToolView(sharedTools, task.template().allowedTools(registered));
        // 子代理 system = 框架基线 + 模板专属提示（SubagentBaseline：通用纪律归框架，
        // 模板只写角色；任务描述由父 agent 每次现场生成，不做预制模板）
        PromptRegistry prompts = new PromptRegistry(SubagentBaseline.compose(task.template().prompt()));
        // 治理与父同配置（ADR-0015 决策 5）：tuning 为 null 表示"用缺省常量治理"，
        // 不是"不治理"——父侧同样是恒建实例（PresenterAssembly.governance）
        ContextGovernance governance = new ContextGovernance(llm, tuning);
        ToolCallingAgent agent = new ToolCallingAgent(llm, tools, task.session(), prompts,
                task.template().effectiveMaxIterations(), governance);
        var reply = agent.send(task.description(), AgentListener.NONE);
        if (reply.completed()) {
            return new Outcome(reply.finalText(), true, null);
        }
        // 迭代上限触达：子代理的成果不能丢——带回工具调用摘要与末次结果摘录，
        // 父 agent 据此可自行汇总或经 send_message 让该子代理续轮（ADR-0015 决策 3
        // 的"结果概要"在未完成路径上的兑现）
        return new Outcome(summarize(reply.toolInvocations()), false,
                "已达迭代上限（" + task.template().effectiveMaxIterations() + " 轮），未产出最终回答");
    }

    /**
     * 未完成时的成果摘要：全部工具调用逐条列名（参数摘要 → 结果体量/错误标注），
     * 末尾附最后一次结果的尾部摘录（父 agent 能直接看到子代理最后收集到什么）。
     */
    private static String summarize(List<ToolInvocation> invocations) {
        if (invocations.isEmpty()) {
            return "（尚无工具调用产出）";
        }
        StringBuilder text = new StringBuilder("已执行 " + invocations.size() + " 次工具调用：");
        for (int i = 0; i < invocations.size(); i++) {
            var invocation = invocations.get(i);
            text.append("\n").append(i + 1).append(". ").append(invocation.tool())
                    .append(" ").append(abbreviate(invocation.argumentsJson(), 70))
                    .append(" → ").append(invocation.isError()
                            ? "错误：" + abbreviate(invocation.result(), 80)
                            : invocation.result().length() + " 字符");
        }
        var last = invocations.get(invocations.size() - 1);
        text.append("\n").append(SubagentManager.EXCERPT_MARKER).append("\n")
                .append(tail(last.result(), 800));
        return text.toString();
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String tail(String text, int max) {
        return text.length() <= max ? text : "…" + text.substring(text.length() - max);
    }
}
