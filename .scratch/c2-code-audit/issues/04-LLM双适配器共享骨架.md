# 04: LLM 双适配器共享骨架

## What to build
两个 LLM 协议适配器（OpenAI 兼容面、Anthropic 面）共享一套流式骨架——SSE 行循环、空闲超时二分语义（未交付可重试/已交付保留输出）、错误翻译、可重试状态码判定、URL 处理、HTTP 客户端构造——各自只保留帧解释差异。此后任一协议面的语义演进两面天然同步，约 150 行整拷重复消除。

证据锚点：审计报告 P2-B 族第 1/2/26 条（骨架逐字同构、SSE 行循环四份、可重试集合与超时常量双份）。

验收标准（用户可感）：两个协议面在帧序、错误语义、空闲超时二分语义上行为同构（对拍测试锁住）；行为敏感重构独立成 commit 可整体回滚。

## Status
in-progress（实现完成待手动验收，2026-09-28）

## Checklist
- [x] 对拍测试先行（AdapterParityTest 3 用例：错误语义 400/429 分类与消息逐字一致、流式增量序、聚合结果等价——抽取前先落绿锁基线）
- [x] 模板方法骨架提取（StreamingHttpAdapter：send/idleGuarded/idleOutcome/errorFrom/effortOf/url/readSse 单点化；HEADER_TIMEOUT 常量化）
- [x] 两适配器改为帧解释器形态（streamLines→streamFrames、[DONE] 终止形态统一消分叉），对拍测试抽取后保持全绿
- [x] 独立 commit（连同对拍测试，可整体回滚）
- [x] CHANGELOG 记账

## Comments

- 2026-09-28 实现：骨架承载完全同构部分，帧解释器（SseFrameHandler 函数式接口，返回 true 终止）是唯一分叉点；lambda 化帧循环的可变聚合状态改单元素容器承载（usage/promptTokens/thinkingBlock 等）。OpenAI aggregateTurn 的 `[DONE]` 由 continue 统一为终止（行为等价——DONE 后无更多帧），消审计发现的形态分叉。llm 全量 72 用例全绿（对拍 3 + 适配器既有 40 + Retrying 等），agent/example 消费方回归绿。

