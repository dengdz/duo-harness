/**
 * duo-harness-agent 模块根包：agent 循环契约与门面——ChatAgent 对话服务、
 * AgentListener 过程回调、AgentReply 任务结果、ToolInvocation 工具调用记录、
 * AuditingAnswerer 审计桥、SessionTitles 标题生成器。域实现按包拆分：
 * {@code governance}（上下文治理）/ {@code skills}（技能）/ {@code plan}（计划模式）/
 * {@code prompt}（提示注册与注入）；工具循环实现在 {@code agent.internal}，
 * 呈现装配在 {@code agent.presenter}，均不对外暴露。
 */
package dev.duo.harness.agent;
