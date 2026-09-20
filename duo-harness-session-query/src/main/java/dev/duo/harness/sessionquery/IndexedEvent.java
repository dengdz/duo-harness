package dev.duo.harness.sessionquery;

/**
 * 索引视角的事件投影：JSONL 行解析出的检索所需最小字段。**刻意不含
 * reasoning**——思考内容物理进不了索引管线的任何一环（ADR-0022 决策 8，
 * "reasoning 不入"由载荷形状保证而非遍历跳过）。
 *
 * @param type     事件类型（SessionEvent.* 常量）
 * @param at       事件时间戳（epoch millis）
 * @param text     事件载荷文本
 * @param toolName 工具名（工具事件携带，其余 null）
 */
public record IndexedEvent(String type, long at, String text, String toolName) {
}
