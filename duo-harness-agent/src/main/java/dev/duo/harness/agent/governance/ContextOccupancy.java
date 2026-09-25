package dev.duo.harness.agent.governance;

/**
 * 上下文占用只读快照：治理计量的同源视图——{@code tokens} 与 compaction 判定取
 * 同一口径（provider 真实用量优先、本地估算兜底，ADR-0009），状态面展示与阈值
 * 对照据此渲染，不自行估算。
 *
 * @param tokens          当前上下文占用（最近响应 prompt+completion，无实测时投影估算）
 * @param thresholdTokens compaction 触发阈值（窗口 × 触发比例）
 * @param windowTokens    模型上下文窗口
 * @param fromProvider    tokens 是否来自 provider 真实用量（false = 估算兜底，展示须标注口径）
 * @param compactionTripped 压缩熔断态（M25 工单 05）：summary 连续失败达阈值后为 true——
 *                         自动压缩暂停、会话照常可用；状态面据此可见（/compact 不受限）
 */
public record ContextOccupancy(long tokens, long thresholdTokens, long windowTokens,
                               boolean fromProvider, boolean compactionTripped) {
}
