/**
 * 呈现位装配域：CLI 与 Web 插件共用的执行链装配单点（ADR-0011）。
 *
 * <p>呈现位插件（CliPlugin / WebPlugin）的装配形状完全同构——LLM 配置装载与重试
 * adapter、上下文治理、ChatAgent 构建、HITL 交互工具查重注册——差异只在呈现件
 * （终端 / 浏览器）与自己的会话策略。本包把同构部分收敛为 {@link dev.duo.harness.agent.presenter.PresenterAssembly}
 * 的静态工厂，两插件各调一次，消除双份装配代码漂移。</p>
 */
package dev.duo.harness.agent.presenter;
