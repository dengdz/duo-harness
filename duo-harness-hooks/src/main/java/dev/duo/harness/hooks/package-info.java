/**
 * hooks 扩展域：复用 Claude Code/Codex hooks 配置格式的外部命令钩子（ADR-0019）。
 *
 * <p>主要类型：{@link dev.duo.harness.hooks.HooksPlugin}（插件行 opt-in、双事件挂载
 * 工具三段管线）、{@link dev.duo.harness.hooks.HooksConfig}（hooks.json 解析与配置
 * 形状）、{@link dev.duo.harness.hooks.HookMatcher}（matcher 三档语义）、
 * {@link dev.duo.harness.hooks.HookRunner}（钩子进程执行：stdin 载荷、限时、三态结局）。
 * 失败语义 fail-open——钩子不是执法边界。</p>
 */
package dev.duo.harness.hooks;
