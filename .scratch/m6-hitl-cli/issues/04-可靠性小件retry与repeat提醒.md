# 04: 可靠性小件——LLM 重试 + 重复调用提醒

**What to build:** 两个互不依赖的小件，合消除"一次抖动就崩 / 重复调用烧满上限"两类可用性硬伤：

1. `RetryingAdapter`（llm 域装饰器）：包住任意 LlmAdapter，对网络异常与 HTTP 429/502/503/504 指数退避重试（默认 3 次：1s/2s/4s）；400/401 等协议与凭证错误直通不重试。装配处一行包装，契约零改动。
2. `RepeatReminderPlugin`（example 域治理插件）：post-execute 结果改写——同一工具 + 相同参数哈希连续重复达阈值（3 次起，逐级加码）时在结果尾部附加提醒文本，不改错误形态；与硬迭代上限软硬互补。

**Blocked by:** None (can start immediately)

**Status:** implemented（2026-09-13，待用户验收）

## Checklist

- [x] RetryingAdapter：5xx/429 重试后成功；重试耗尽抛原始错误；400/401 直通不重试（MockOpenAiServer 先例）
- [x] 重试参数可配（maxAttempts / initialBackoffMs，供工单 05 接入 yml）
- [x] RepeatReminderPlugin：第 3 次重复起结果尾部出现提醒、逐级加码；不同工具 / 不同参数互不计数；结果不转错误形态
- [x] 插件可经 yml 一行挂载（与 write-protector 同构）
- [x] limitations.md 删除 M5 #2（重试落地即消账）；CHANGELOG 未发布段记两件
- [x] 文档同步：词汇表无新词条（repeat 提醒是 post-execute 改写，非 guard——不得复用"guard"名）

## 实现记录（2026-09-13）

- `RetryableLlmException`（PluginException 子类，语义标记）：OpenAiCompatAdapter 对网络 IOException 与 429/502/503/504 抛出，400/401 仍普通 PluginException——装饰器按异常类型而非消息文本判定，无脆弱解析
- `RetryingAdapter` 流式安全语义：仅在零增量交付时重试（已发 chunk 后失败重试会造成内容重复，原样上抛）；退避 ×2 递增、参数可配（测试 1ms）
- `RepeatReminderPlugin`：post-execute around（先放行内层再改写）；同工具+同参数 key 连续计数，错误结果计数但不附加提醒；阈值配置非法回落默认
- 测试：RetryingAdapterTest 6 例 + RepeatReminderPluginTest 4 例；过程中修了一个测试自身 bug（阈值 2 用例漏调第一次 execute）

## Comments

spec：[../spec.md](../spec.md)。grill Q5 定案：重试是装饰器而非循环内逻辑；提醒是 post-execute 改写而非 guard（guard = 单调否决，语义不可污染）。
