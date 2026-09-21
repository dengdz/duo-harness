# LLM 调用层（DSH）

> 源锚点：`ddefc45fbc7f8e46dd73185e68295696d1297887`（2026-09-21 本地核验）。M22 探测里程碑工单 09（T-13）产物，五段统一模板。接口参考见 [../模块手册.md](../模块手册.md)。

## 机制全貌

DSH 把 LLM 调用组织为「注册表 + 瀑布 + 适配器」三段：agent-loop 在每步先 `llm.prepareCall(config)` 绑定适配器代际，再从会话日志重建请求（`markAgentLoopRequest` + 深冻结，core/agent-loop/src/agent.ts:542,611），经 `preparedCall.stream(request)`（agent.ts:391）进入 `LlmRuntime.stream` → `llm/stream` waterfall（llm/src/index.ts:1114-1124）→ 末梢 `adapterStream`：校验 config 一致、把 file 投影为句柄文本、文本模型图片投影为替代文本、剥离他适配器 replay 态后 dispatch，逐 chunk 转发；适配器任何 throw 归一为终结 finish chunk（index.ts:1015-1136）。适配器经 `registerAdapter(['deepseek-official'])` 注册（llm-deepseek/src/index.ts:133），`DeepSeekAdapter.implementation()` 按 config 代际在 messages / chat-completions 两实现间分发（llm-deepseek/src/adapter.ts:19-41）；config 每请求经 thunk 解析，坏快照保旧代际（llm-deepseek/src/index.ts:63-82）。

## 关键流程

① **流式**：`StreamChunk` 七种（block-start/text-delta/reasoning-delta/tool-call-delta/block-end/usage/finish），usage 先于 finish、finish 后无 chunk，finish 可携带 `replayState`（llm/src/types.ts:426-438）；`BlockAssembler` 容忍 delta-only、忽略 block-end 后的迟到 delta（llm/src/assembler.ts:62-80）。适配器边界失败转终结 chunk：`signal.aborted` 或 code=ABORTED → `aborted`，否则 `error`（index.ts:1128-1136）；yield 在 adapter-owned try 之外，消费者/中间件异常保持抛出（index.ts:1087-1089）。

② **messages 适配器**：`generate` 用 `idleWatchdog`（默认 300s，common/defaults.ts:4）包住读取，超时转 `TIMEOUT`、abort 转 `ABORTED`（protocols/messages/adapter.ts:64-86）；请求组装时 system 支持 `in-history` 中途更新（serialize.ts:83-105），assistant 的 reasoning 块回传 `thinking`+签名（签名来自 durable replayState，serialize.ts:31-34、replay.ts:48-63），tool_use/result 严格配对校验，`thinking:{enabled}`+`output_config.effort`（session-title 强制 off，默认 high，serialize.ts:122-131）；图片走 Files API file_id，失败降级 inline base64（adapter.ts:99-112）；HTTP/in-band 错误统一归类 AUTH/QUOTA/RATE_LIMIT/CONTEXT_WINDOW_EXCEEDED/SERVER 并解析 Retry-After 与 request-id（transport.ts:22-44）；翻译器校验 message_start→blocks→message_stop 顺序，缺 message_stop 抛 STREAM_CLOSED（translate.ts:104-166）。chat-completions 差异：Bearer + `/chat/completions`，`reasoning_content`→reasoning 块，finish 与 usage 延迟到 `[DONE]` 统一发射，`prompt_cache_hit_tokens` 从 `prompt_tokens` 中拆出（disjoint 口径，translate.ts:55-72），未识别 finish_reason 直接转 error finish（translate.ts:32-44）。

③ **重试**：策略归 provider 路由所有、注册时捕获（index.ts:439-445）；默认 normal/5 次/500ms 起/10s 封顶/jitter 0.1，可重试码 EMPTY_RESPONSE/RATE_LIMIT/SERVER/TIMEOUT/TRANSPORT（llm/src/retry-policy.ts:14-24）。llm-retry 插件挂 `agent/request-error` 瀑布：匹配码→指数退避，`providerRetryAfterMs` 在 maxDelay 内优先采信，超出则 normal 放弃、always 改本地延迟（llm-retry/src/index.ts:226-238）；先 append `llm/retry` 再可取消等待，醒后 append `llm/retry-started` 并返回 `{kind:'retry'}`（index.ts:188-191）。重试计数存 session projection，`step/start`/`turn/end` 清零（index.ts:130-138）。**请求重建不在 retry 侧**：loop `continue` 后整体重跑 prepareRequest+buildRequest，请求是日志的纯函数（agent-loop/agent.ts:362-380,461-464）。

④ **计量**：`measure` 折叠日志到锚点（最近成功 assistant/message 的 usage+header）；仅当 header 匹配且 usage 总量 ≥ 同一锚点启发式全价时才采信真实 usage，否则全量启发式重估（token-meter/src/index.ts:158-181）；启发式 4 字符/token、块/角色开销 4（estimate.ts:13-19），工具 schema 按 header JSON 计价；图片视觉 token 由适配器 `imageRequestPricing` 提供（index.ts:194-198）。

## 接口与参数要点

- `LlmAdapter` 契约：仅 `stream` 抽象必选，`prepareCall` 默认实现绑定 resolveModel+stream，动态适配器可覆写防代际混用（llm/src/index.ts:203-285）；`PreparedLlmCall` 一次性派发（二次调用/改动 config → `INVALID_PREPARED_CALL`，index.ts:937-964）。
- 关键默认值：protocol 默认 messages（config.ts:81）；baseURL 缺省取 `$DEEPSEEK_BASE_URL`→`api.deepseek.com`（messages 走 `/anthropic`，config.ts:104-107,292-293）；maxTokens 256k、contextWindow 1M、streamIdleTimeoutMs 300s（defaults.ts:4-8）；llm-retry 自身无配置，retryPolicy 必须写在 provider 段（llm-retry/index.ts:30-37）。
- 每次 stream 冻结一份连接快照+密钥，进行中请求不受设置变更影响（chat-completions/adapter.ts:170-203）。

## 边界与坑

- prepareCall 代际绑定是硬约束；retryPolicy 是唯一注册期捕获、每请求解析刷不掉的事实，llm-deepseek 用 `registration.replace` 同步换路由而非重注册（llm-deepseek/index.ts:134-145）。
- 适配器失败是终结 finish 而非异常；但中间件/消费者失败仍抛——两层错误通道不可混用。
- 重试与 turn 边界：计数在 step/start、turn/end 清零；跨 turn 的陈旧 projection 条目按 retryId 幂等去重（llm-retry/index.ts:134-137）。
- 请求不可变纪律：loop 请求深冻结、改写即抛；waterfall 监听只读（llm/src/index.ts:60-74）。
- 图片 Files 上传重试是适配器内部 while 循环（beginAttempt/inline 降级），与 llm-retry 的策略重试是两套互不知晓的机制。

## 对 duo 的启示

duo 现状为 OpenAI 兼容 Chat Completions 单协议、SSE 流式、reasoning_content 随事件持久化全量回传（M7）、idle 超时（M16）、真实 usage（M10）。对照三条：

1. **双协议适配层**——DSH 用 `implementation()` 按配置代际分发 messages/chat-completions，共享 file-store/定价/默认值（llm-deepseek/adapter.ts:19-41），duo 若接 Anthropic 风格协议可复用此「协议子适配器 + 共享公共件」形态，且协议校验放 resolve 阶段 fail-loud。
2. **重试从日志重建**——DSH 的 retry 只输出决策信号，请求由 loop 从 session log 纯函数重建、计数走 durable projection 并随 turn 清零，duo 可借鉴「重试状态可持久化 + turn 边界归零」而无需缓存请求体。
3. **usage 采信规则**——DSH 仅在真实 usage ≥ 启发式下界且 header 匹配时采信、否则重估（token-meter/index.ts:158-181），并把 cache 命中从 `prompt_tokens` 拆成 disjoint 桶（chat-completions/translate.ts:55-72），duo 的 M10 可补一条「真实值不小于估算下界」一致性校验，防止 provider 少报污染压缩决策。
