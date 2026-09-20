/**
 * 会话检索域（M21，ADR-0022 决策 8）：后端无关的会话全文检索服务——一期
 * 纯 Java 内存倒排索引（懒构建首次搜索才扫 + 文件 mtime/size 增量；分词 AND +
 * snippet 高亮），接口语义对齐 FTS5 形态防后期转 SQLite 的切换漂移。
 *
 * <p>索引内容对齐 DSH 清单：消息文本 / tool 调用名+参数 / tool 结果 / todo /
 * turn 错误；reasoning 与其余治理事件不入索引（{@link EventTextExtractor}
 * 物理不读 reasoning 字段，用例钉住）。子代理会话（sessions/subagents/ 子目录）
 * 不索引——索引只扫会话目录顶层。opt-in = yml 装 session-query 插件行，
 * 不装零感知。</p>
 */
package dev.duo.harness.sessionquery;
