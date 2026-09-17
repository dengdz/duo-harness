package dev.duo.harness.agent.subagent;

import dev.duo.harness.agent.governance.ContextGovernance;
import dev.duo.harness.llm.LlmAdapter;
import dev.duo.harness.session.Session;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 子代理宿主（M15，ADR-0015 决策 1/5）：呈现位在装配时发布的运行期构件供给——
 * 子代理执行链所需的父侧三件。**依赖方向由呈现位指向 subagent 插件**：呈现位
 * 不必知道 subagent 是否存在（未配置模板部署零感知），而 SubagentPlugin 经
 * inject 声明本服务取得执行链（子代理本就必须有父 agent 运行环境，硬依赖语义
 * 成立）。
 *
 * <p>会话经 {@link Supplier} 提供——呈现位换绑（/new、/switch）后子代理写的是
 * 新会话的引用事件。父的 LLM 与治理阈值同源继承（ADR-0015 决策 5）：子代理恒建
 * 治理实例，{@code tuning} 为 null 即与前一样用缺省常量（**不是**"不治理"）。</p>
 */
public record SubagentHost(LlmAdapter llm, ContextGovernance.Tuning tuning,
                           Supplier<Session> currentSession) {

    /**
     * 服务名（呈现位发布、SubagentPlugin 经 inject 声明读取）。取单小写词是内核
     * 契约要求——视图方法名即服务名，而方法名不能含连字符（服务名须能作为
     * 视图接口的方法名书写）。
     */
    public static final String SERVICE_NAME = "presenter";

    /** 构造时校验非空——错误前移到构造点。 */
    public SubagentHost {
        Objects.requireNonNull(llm, "llm");
        Objects.requireNonNull(currentSession, "currentSession");
    }
}
