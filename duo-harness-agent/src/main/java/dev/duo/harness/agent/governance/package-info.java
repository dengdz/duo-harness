/**
 * 上下文治理域：读侧治理管线——spill（超大工具结果落盘）→ 工具结果修剪 →
 * token 计量（真实 usage 优先，ADR-0009）→ compaction（远端历史折叠）。
 * 治理只影响模型看到的请求，会话日志永远完整（ADR-0013）。
 */
package dev.duo.harness.agent.governance;
