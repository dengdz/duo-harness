# LLM 调用层（ZCode）

> 源锚点：`872ad960de7ec172591f7e1952f7849229f94521`（2026-09-21 本地核验）。M22 探测里程碑工单 09（T-13）产物，五段统一模板。接口参考见 [../模块手册.md](../模块手册.md)。

## 机制全貌

Vercel AI SDK 的薄封装栈：`AiSdkModelAdapter.createModel`（runner.ts:138）绑定 provider/model 快照后产出带 `executor.{generateText,streamText}` 的 Model（runner.ts:268-285）；流式走 `runStreamText`（runner-stream.ts:93）——attempt 循环内先进程级准入 `admitAttempt`（runner-stream.ts:243），再 `createStreamTextOptions`→`runtime.streamText`（即 ai 的 streamText，runner-runtime.ts:50-51），逐 chunk 经 `toModelStreamEvent` 归一为 `ModelStreamEvent`，过 `StreamingToolCallAssembler`，按 retry-boundary 规则 yield 给 core turn 循环。要点：

- provider kind 三值 `"openai"|"anthropic"|"openai-compatible"`（model-execution.ts:31），factory switch 分别用 createOpenAI(responses)/createAnthropic(+compat fetch)/createOpenAICompatible（model-execution.ts:277-300）；registry 面：`RegistryProviderConfig`（provider/src/resolver.ts:32），access 为 api-key|zhipu-account 判别联合，apiType=anthropic-messages|openai-chat-completions|openai-responses（provider/src/config/provider-data-schema.ts:3-45）。
- `maxOutputTokens` 必须显式，缺失即抛 InvalidModelRequest（runner.ts:331-339）。

## 关键流程

① **流式**：streamText 参数 `maxRetries:0`（重试全归 adapter）、`allowSystemInMessages:true`、headers=provider+归因头、cacheControl 转成 `providerOptions.anthropic.cacheControl`（runner-options.ts:119-156；transform.ts:495-513）；compact 模式开 `includeRawChunks` 观察原生边界（runner-options.ts:153）。AI SDK 事件归一在 runner-normalization.ts:64-162（text-delta/tool-input-*/tool-call/finish/error→统一事件）。retry-safe 前奏（start、空 delta）先缓存进 `pendingRetrySafeEvents`，见到真实事件才放行并置 `emittedRetryBoundaryEvent`（runner-stream.ts:1075-1115）——此后禁 adapter 重试（1470-1475）。

② **工具装配**：assembler 按 id 去重；已进 `tool_input_start` 生命周期而未见 `tool_input_end` 时，final `tool_call` **不得**顶替缺失的 end，只丢弃首次 JSON 可解析合成（streaming-tool-call-assembler.ts:82-89）；已完整的 call 只补 providerExecuted 元数据并归一 name/input（91-115）；`finish` 先 flush 再透传（34-38）。

③ **重试/超时**：默认 maxAttempts=11（=10 重试）、base 2s、factor 2、cap 60s、jitter（0.5-1x）（retry-policy.ts:13-30；runner-retry.ts:51-55）；env `ZCODE_MODEL_RETRY_MAX_RETRIES/BASE_DELAY_MS/BACKOFF_FACTOR/MAX_DELAY_MS`（retry-policy.ts:18-21）。Retry-After ≤5min 或小于指数值时优先（runner-retry.ts:223-233）。idle 超时基线 600s，每次重试 +30s（stream-idle-timeout.ts:5,23）。空补全额外重试 1 次，finish 事件先扣住防泄漏（empty-completion-retry.ts:8；runner-stream.ts:407-411,529-577）。off-peak 排队 429 豁免预算（`attempt-=1`，runner-stream.ts:722-725,857-860）；unbounded 预算哨兵 0（retry-budget.ts:14）。

④ **reasoning**：仅 anthropic 在逻辑请求入口做一次历史投影（runner.ts:358-372）——跨模型签名块剔除（兼容组 builtin/zai/bigmodel Individual+Team，reasoning-history-normalization.ts:18-29）、孤立/尾部 reasoning 清理、空 assistant 补 "(no content)"、相邻 user 合并。签名拒绝（400+窄化文案匹配，76-92）触发一次性修复重放：只换请求副本、不占预算、新 requestId（runner-stream.ts:181-205）。unsigned thinking 用空 signature `""` 保留（anthropic-reasoning-metadata.ts:19-34）。

⑤ **失败分类**：`classifyModelFailure`（failure-classifier.ts:81-306）顺序：类型化 Invalid→abort→idle timeout（可重试）→ProviderBusinessError（含业务码映射表、嵌套 cause/responseBodySummary/BigModel `[1234]` 括号提取，509-544,627-633）→429/529 可重试→401/403 不可重试→400/422/ContextExceeded→≥5xx→TLS/代理/网络→provider 标记兜底。`TerminalStreamChunkError` 把流内终止错误带出 chunk 处理进外层 catch（runner-retry.ts:29-36）。

## 接口与参数要点

- `ModelStreamEvent` 联合（contracts/src/model/index.ts:715+）：start、compact_stream_boundary（5 种 boundary）、text/reasoning 三段、tool_input 三段、tool_call、finish、error。
- 重试默认 11 次/2s/×2/60s/jitter；idle 600s+30s×重试；空补全 1 次；attempt 清理上限 1s（runner-stream.ts:91）；Retry-After 上限 5min。
- 多 statusSink 用 allSettled 隔离（runner.ts:116-136）。

## 边界与坑

- **end gate**：final tool_call 不能补 tool_input_end，否则半截 JSON 会被当完整调用执行。
- Stop/abort：`readNextWithStreamIdleTimeout` 必须监听 abort 主动结束 `iterator.next()` 等待，否则 UI 卡到 idle 超时（stream-idle-timeout.ts:139-143）。
- AI SDK fullStream tee：失败 attempt 须 abort+`consumeStream` 双通道收口，否则旧请求占住连接、后续重试卡在发送前（runner-stream.ts:861-916）。
- 历史归一只做一次/逻辑请求，放 serializer 会与签名修复互相破坏（runner.ts:362-366）。
- `ZCODE_RUNTIME_ENV=test` 不写 model IO（runner-debug.ts:64-67）；空补全 finish 先扣存防首 attempt 泄漏（runner-stream.ts:407-411）；退避 sleep 前先归还准入票（runner-stream.ts:824-827）。

## 对 duo 的启示

duo 现状=单协议 OpenAI 兼容+SSE+M16 idle 超时+M7 reasoning 全量回传。对照三条：

1. **工具装配状态机**：assembler 的 id 去重+end-gate（final call 不顶替缺失 end）+finish flush 次序，直接可移植到 duo 的 SSE tool_calls 分片拼接，防半截 JSON 执行。
2. **失败分类枚举**：classifier 的有序降级链（业务码映射→HTTP 段→网络段→provider 兜底）+Retry-After 上限 5min+jitter，可替换 duo 目前散落的 status 判断，并为 idle 超时重试引入 +30s 递增与空补全单次重试。
3. **reasoning 修复重放**：duo M7 全量回传 reasoning，可借鉴「入口一次性投影+签名/越界块剔除+一次性免费修复 attempt」模式，避免污染 canonical history。
