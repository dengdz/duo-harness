# 治理计量切 provider 真实 usage，估算降为兜底

Status: 批准（2026-09-14，M10 grill 裁定）

上下文治理（spill / 工具结果修剪 / compaction，M9 交付）的触发判定一直以本地估算（按字符密度折算 token）为唯一数据源，误差 ±10-20%。本决策把计量源切换为 provider 报告的真实 usage，估算仅作 provider 未报时的兜底。修正 M9 spec 的估算定案；DSH 0.1.5 的 usage 全链路（请求固定带 `stream_options: {include_usage: true}`、usage 落 `assistant/message` 事件字段、Host 侧投影消费）为参照物（[docs/research/DSH/](../research/DSH/)）。

## 背景

M9 grill 时已识别到 provider 可报真实值（OpenAI 兼容流式协议的 usage chunk），但按增强项延后，治理先行用估算顶着。实践暴露两个问题：

1. **阈值语义失真**。治理阈值（如窗口占比 85% 触发 compaction）乘上 ±10-20% 的估算误差，实际触发点漂移明显；短会话里治理"该动没动 / 不该动乱动"都可能出现。
2. **可见性信用损耗**。状态面展示占用后（M10），展示值与治理判定若同源估算，用户无法判断数字可信度；真实值是唯一能建立信用的口径。

OpenAI 兼容流式协议支持请求级 `stream_options: {"include_usage": true}`，启用后流末尾附带官方 usage 统计；DeepSeek 等主流 provider 均支持。DSH 的实践证明该链路稳定：usage 随 `assistant/message` 事件落 durable 日志，投影侧只读现成值，且**不做估算兜底**（provider 未报就不渲染）。

## 决策

1. **请求侧**：OpenAI 兼容适配器统一携带 `include_usage`；从流末 usage chunk 捕获，未收到时字段缺席。
2. **存储侧**：usage 作为 `assistant/message` 事件的**可选字段**落会话日志——沿用 M4 可选字段先例，向后兼容（历史会话无此字段照常加载），不设独立 usage 记录（与消息同行，避免二次对账）。
3. **消费侧**：治理判定与状态面展示共用同一取数逻辑——**真实值优先**（最近一次响应的 prompt + completion tokens，即下一轮请求将携带的上下文近似），**provider 未报时退回本地估算**。
4. **估算路径保留不删**。与 DSH 的差异点：DSH 的 UI 不渲染无 usage 的数据可以容忍，duo 的治理是硬闸门——provider 不报（非 OpenAI 兼容、协议降级、异常流）时治理必须继续工作，估算兜底是 fail-safe 需求，不是技术债。

## 拒绝的选项

- **维持纯估算**（M9 现状）：误差永久存在，可见化后信用问题更突出；provider 能报而不用没有道理。
- **真实值唯一、删估算路径**（DSH 式）：治理在 provider 不报时静默失效或需另行降级设计，违反硬闸门定位；为省一条代码路径引入失效模式不值得。
- **usage 独立事件/独立记录**：事件词汇膨胀且要与 assistant/message 对账；可选字段随消息落日志最简单、顺序天然一致。

## Consequences

- `assistant/message` 事件词汇扩展一个可选字段——公开契约变更，按 M4 先例向后兼容；消费方（Web SSE、持久化）无须感知。
- 治理测试基建新增"真实值路径"用例；估算路径用例保留（兜底仍在）。
- 状态面占用数字与治理判定同源同口径，"占用 N% / 阈值 M%"的对照从此可信。
- 计费口径（cache 命中、分桶明细）不在本期——数据已落日志，后续按需投影。
- provider 报告口径差异（如 prompt_tokens 是否含缓存命中）本期不区分——窗口占用语义下都占窗口；计费语义启用时再引入分桶。
