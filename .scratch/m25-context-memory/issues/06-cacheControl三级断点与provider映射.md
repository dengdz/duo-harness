# 06: cacheControl 三级断点与 provider 映射

## What to build
请求侧的缓存省钱：对每轮重复携带的稳定前缀打三级缓存断点（身份前缀 / 稳定身份 / 动态段），provider 侧命中缓存即省 token 提速。不支持的 provider 静默降级（不带断点参数、零报错）。用量透出：缓存命中与计费差异在用量统计可见（用户故事 16）。交付后的可感行为：多轮对话的 token 账单里出现缓存命中项。

设计要点：三级断点的具体划分边界与各家 provider 的映射策略按探测工单 10（系统提示与上下文工程）落地；断点失效自然回源（用户故事 13，无隐性成本惊喜）。

## Blocked by
01

## Status
in-progress（实现与自动化验证完成；验收件 = 适配器断点/降级/透出测试套件 + 可选真机缓存命中演示）

## Checklist
- [x] 三级断点划分与请求装配（身份前缀 / 稳定身份 / 动态段）
- [x] provider 映射（anthropic / openai-compat / glm / deepseek 四行策略；不支持静默降级零报错）
- [x] 用量透出（缓存命中在用量统计可见）
- [x] tdd 红绿循环（断点位置断言 + 降级用例，seam 见 Comments 记档）
- [x] 术语表「cacheControl 断点」词条同 diff

## Comments
- 2026-09-26：**机制裁定（探测工单 10 依据）**：三级断点 = 身份前缀（system 首段 = yml 用户指令，跨会话不变）/ 稳定身份（静态片段 AGENTS.md/记忆指南/技能清单——注册表不变即逐轮逐字节同）/ 动态段（会话消息，断点打末条消息 content 块）；仅 anthropic 打断点（system 组块数组 + `cache_control: ephemeral`，块间 \n\n 还原逐字节等价；单段 system 退字符串旧路径零变化）；openai-compat/glm/deepseek = provider 自动前缀缓存零参数静默享受。切分边界 = 首个双换行（与 PromptRegistry 组装序一致：userPrompt 最前 + 片段空行拼接——javadoc 记档边界假设与自含空行的容忍性）。**provider 行验证现状**：anthropic/openai-compat/deepseek 有专项用例（deepseek 经 openai-compat 适配器，缓存字段 prompt_cache_hit_tokens 双字段兼容解析）；glm 与 openai-compat 同适配器同字段，不另设用例（记档）。
- 2026-09-26：**用量透出链**：adapter 解析（anthropic cache_read_input_tokens / openai-compat prompt_tokens_details.cached_tokens / deepseek prompt_cache_hit_tokens 取大防双报）→ llm.TokenUsage(4 参) → agent 映射 → session.TokenUsage(4 参，同构副本域间映射) → assistant/message 事件 JSONL（cachedTokens >0 落盘，旧日志缺字段读 0）→ headless NDJSON usage 帧（Jackson record 自动含新组件）。状态面暂不加呈现位（用量面在 JSONL/headless 两处已可见；Web 状态面加缓存行挂 07 或收尾单按需议）。
- 2026-09-26：**TDD seam**（自主模式按 spec Testing Decisions 定）：①CacheControl.split 纯函数 + TokenUsage 四参形态（CacheControlTest 3 用例）②anthropic 请求体断点断言（两段组块断点 1/2 + 末条 content 块断点 3 + 中间消息干净 + 单段退字符串 + 跨轮缓存计数不泄漏）③openai-compat cached_tokens 透出 + 请求体零断点参数降级。适配器级用例走 mock server 请求体捕获（先例 effort 用例同款）。
- 2026-09-26：全量 984 用例 0 失败（2 既有 skip）。
- **真机验收第一轮（2026-09-26）抓到 BUG-20260926-01 并修复（acc15a0）**：anthropic 行对含历史会话一律 422（content 块缺判别字段 type——断点 3 组块 set("text") 捷径漏 type；mock 回显器测不出协议违约）。修复 = 组块补 `put("type","text")` + 用例块级 type 断言 + MockAnthropicServer 升级最小协议校验（同族第二击升结构化防线）。**真机冒烟守则记档**：anthropic 行改动后必跑真机 2 轮对话冒烟。
- **验收对照表（2026-09-26 备，duo-acceptance 工单级三件套；命令已自跑验证）**：
  - **路径 A · 测试套件**（一条命令，42 用例）：
    `./mvnw -q -pl duo-harness-llm -am test -Dtest='CacheControlTest,AnthropicMessagesAdapterTest,OpenAiCompatAdapterTest' -Dsurefire.failIfNoSpecifiedTests=false`
    预期标志行（逐条对照）：
    1. `=== 套件：OpenAiCompatAdapterTest —— ...流式空闲超时二分（23 用例） ===`
    2. `=== 套件：AnthropicMessagesAdapterTest —— ...usage 双帧、错误呈现（16 用例） ===`
    3. `=== 套件：CacheControlTest —— 缓存断点：三级划分、TokenUsage 缓存字段、provider 映射（3 用例） ===`
    4. 全程无 `[ERROR]`、命令退出码 0
  - **路径 B · 真机缓存命中**（anthropic provider；修复 422 后的复验）：
    启动命令 = 运行Demo.md 命令块整块复制（AgentReplMain）；`/new` 后连聊 3 轮（每轮一个不同问题），`/exit` 后终端跑 `F=$(ls -t ~/.duo/agent-sessions/*.jsonl | head -1) && grep -o '"cachedTokens":[0-9]*' "$F" | tail -5`
    预期标志行：
    1. 三轮对话**全部正常回答、零 422**（BUG-20260926-01 修复判据——上轮 422 复现场景即此）
    2. grep 第 2 轮起输出非零 `cachedTokens`（如 `"cachedTokens":5000` 量级；第 1 轮 0/缺席正常——首次写缓存）
    3. （可选）`grep -o '"promptTokens":[0-9]*' "$F" | tail -5`——promptTokens 随轮增长，cachedTokens 同向增长
- **待用户验收（转 done 前最后一步）**：
  1. 测试套件亲手跑（命令含旗标）：`./mvnw -q -pl duo-harness-llm -am test -Dtest='CacheControlTest,AnthropicMessagesAdapterTest,OpenAiCompatAdapterTest' -Dsurefire.failIfNoSpecifiedTests=false`——3 套件（3+16+23=42 用例）全绿；
  2. （可选，anthropic provider 用户）真机缓存命中演示：CLI 连聊 3+ 轮后终端 `F=$(ls -t ~/.duo/agent-sessions/*.jsonl | head 1) && grep -o '"cachedTokens":[0-9]*' "$F" | tail -3`——第 2 轮起应出现非零 cachedTokens（首轮为 0 正常——首次写缓存）。

## 审查轮（2026-09-26·第 1 轮·四轴两路合并）

**覆盖**：11 文件 = 已审 11 + 跳过 0（行级主代码 6/6 100%）

### 阻断
- **CHANGELOG 漏记** [Standards，红线 6，与 05 同模式复犯] → **已修**：补三级断点 + 四行映射 + 用量透出记账
- **`AnthropicMessagesAdapter.java` 断点 3 挂错位置** [行级，引 Anthropic 官方文档] → **已修**：消息顶层改 content 块（字符串 content 先组块再挂，末块打 ephemeral）；用例断言改 content 块形态
- **`cacheReadTokens` 实例字段跨轮泄漏** [行级：适配器跨轮复用，第 1 轮命中值残留到第 2 轮误报] → **已修**：收局部变量（与 promptTokens 同生命周期）+ 跨轮不泄漏专项用例
- **deepseek 行「显式降级」javadoc 失实** [Standards：全 diff 无对应实现且 OpenAI 解析不含异构字段] → **已修**：适配器双字段兼容解析（cached_tokens / prompt_cache_hit_tokens 取大防双报），javadoc 同步
- **孤儿 javadoc**（aggregateTurn 注释被插入字段隔断）[Standards/行级共振] → **已修**：字段收局部后自然消除
- **术语表缺词条** [Spec] → **已修**：cacheControl 断点词条（会话体验域，压缩熔断姊妹链）

### 建议
- **两块间分隔符丢失**（组块拼接 ≠ 原 system 逐字节）[行级] → **已修**：body 块头部还原 \n\n，拼接逐字节等价（断言锁定）
- **空 prefix 防御**（system 以空行开头产出空文本块被 Anthropic 拒）[行级] → **已修**：prefix 空白归并 null，调用方跳过该块
- **split 边界假设记档**（yml systemPrompt 自含空行时边界漂移，功能无影响）[Standards] → **已修**：javadoc 记边界与容忍性
- **测试横幅计数失准 ×3** [行级] → **已修**：3/16/23 实数
- **用量透出面单一（仅 JSONL/headless）** [Spec 弱达标] → **已修（记档）**：透出链两处可见；Web 状态面缓存行挂 07 或收尾单按需议（不在本单膨胀）
- 记档不修：glm 行不另设用例（同适配器同字段，openai-compat 用例覆盖）/ 3 参兼容构造 main 零调用（旧日志反序列化路径仍需，保留）/ 协议字面量直写（库内既有风格）

### 测试覆盖
- 3 套件 42 用例：CacheControl 3（划分/单段/四参形态）+ Anthropic 16（含断点位置/单段回退/跨轮不泄漏/缓存透出）+ OpenAI 23（含 cached_tokens 透出/零断点降级）
- mock server 工厂扩展（messageStart 缓存参数/usageChunk 缓存参数）随用例同 diff
