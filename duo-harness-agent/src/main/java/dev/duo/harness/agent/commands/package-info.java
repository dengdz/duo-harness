/**
 * 斜杠命令体系（M19，ADR-0020）：agent 域的 "commands" 服务与共享分发入口。
 *
 * <p>命令是"操作 harness"的用户指令（与技能"替用户说话"分界——进不进模型历史）。
 * 插件经 {@link dev.duo.harness.agent.commands.CommandsPlugin} 发布的注册表注册
 * 命令（随作用域自动摘除）；呈现位经 {@link dev.duo.harness.agent.commands.CommandsRegistry#dispatch}
 * 按"命令注册表 → 技能直调 → 未知命令报错"的顺序解释输入。执行落
 * {@code command/run} / {@code command/done} 审计两事件（投影排除）；agent 单飞
 * 期间 busySafe 分级裁决（缺省 false，fail-closed）。</p>
 */
package dev.duo.harness.agent.commands;
