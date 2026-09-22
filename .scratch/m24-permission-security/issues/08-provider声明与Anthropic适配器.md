# 08: provider 声明与 Anthropic-messages 适配器

## What to build

yml 增 `llm.provider` 四值显式声明：openai-compat（缺省，兼容现状零改）/ anthropic / deepseek / glm——决定适配器选型、鉴权头形态与 /effort 映射策略，provider 不再由 baseUrl 隐式表达；新增 Anthropic-messages 协议适配器（与 OpenAiCompat 并列的仅有的两个协议实现）：SSE 流式、system/messages 结构映射、tool_use/tool_result 块双向映射、`x-api-key` + `anthropic-version` 鉴权头；不引官方 SDK（零新依赖，红线 4 精神）；装配按 provider 声明选型接线。

决策依据：ADR-0026 决策七（spec 期对账裁定：/effort Anthropic 行 thinking+budget 需真适配器落地，原 1.0 后菜单提进本期）；docs/research/DSH/LLM与上下文/LLM调用层.md（双协议适配层形态参照）。

## Blocked by

无（可立即开工）

## Status
done

## Checklist
- [x] `llm.provider` 解析与缺省兼容（LlmConfigTest 扩展）
- [x] Anthropic-messages 适配器：SSE + tool_use 双向映射 + 鉴权头（新测试面，先例 OpenAiCompatAdapterTest）
- [x] 装配选型接线（先例 BootTest）
- [x] 工单级验收件：provider=anthropic 真端点跑通对话 + 工具调用（2026-09-23 用户委托代跑通过，DeepSeek Anthropic 网关）
- [x] CHANGELOG 记账（0.19.0 段）

## Comments
- 2026-09-23：实现与三轴审查完成（报告见下），全量 BUILD SUCCESS（848 用例 0 失败，2 既有 skip）。
- 2026-09-23：**验收通过（用户委托代跑，headless --json 真端点）**：发现 DeepSeek 官方原生支持 Anthropic 协议（base_url=https://api.deepseek.com/anthropic，x-api-key 完全支持、anthropic-version 忽略）——无需 Anthropic key，用户现有 DeepSeek key 直接可用。实测两场景：①直答「介绍你自己」→ SSE text_delta 流式 + final + usage 3900 tokens 真实回传；②工具闭环「读 pom.xml」→ tool_use(read) 发起 → tool_result 回传 → 模型正确答出 groupId=dev.duo。适配器 baseUrl 拼 /v1/messages 与 DeepSeek 网关路径完全兼容。配置已代写（provider=anthropic / model=deepseek-flash），原配置备份于 ~/.duo/config.yml.bak。
- 备忘：DeepSeek 网关对 claude 模型名有映射（claude-opus-*→deepseek-v4-pro、claude-sonnet/haiku-*→deepseek-flash），也可直传 DeepSeek 模型名；thinking 字段网关「支持但忽略 budget_tokens」——工单 10 的 Anthropic thinking 映射在 DeepSeek 网关上会降级为普通思考，真 thinking+budget 需真 Anthropic 端点。
- **已知限制记档**：① max_tokens 协议必填，缺省 8192 常量（可配置化随工单 10 思考等级）；② 图片仅 inline data URI 形态（files 投递为 DeepSeek 专有，Anthropic 面跳过）；③ 连续 USER（非 TOOL）历史不产生（内部历史形态保证），适配器未做防御合并；④ 思考参数（thinking+budget）随工单 10 接入，本单仅结构就位。
- **记档不修（审查处置）**：两适配器骨架（stream/streamTurn/idle/error）约 85 行同构——工单 10 改思考映射时一并提取公共件；errorFrom 先 new 再 getMessage 的绕行写法与魔法数字 10s 与 OpenAI 面同构；装配选型分支（withRetry 单行三元）无独立测试（轻缺口，冒烟由装配级与验收覆盖）。
- 模块划分.md 的 llm 行与 internal 包边界描述同 diff 更新（双协议适配器）。

## 审查报告（第 1 轮·三轴）：工单 08 全 diff（基点 5971308 工作树，4 改 + 3 新增）

**覆盖**：7 文件 = 已审 7 + 跳过 0（覆盖率 100%）；行级轴名单 4 文件（OCR preview），测试/文档由 Standards/Spec 轴覆盖，全集口径 7/7

### 阻断
- **CHANGELOG 未记账**（红线 6，Standards/Spec 双轴同点）——已修：0.19.0 段补工单 08 条目
- **docs/04-架构/模块划分.md 两处失真**（红线 3）：llm 模块行与 internal 包边界仍写「OpenAI 兼容适配器」，现 internal 下为双协议适配器——已修：两处更新并注 provider 选型

### 建议
- 行级轴 medium：Anthropic SSE `{"type":"error"}` 帧被静默吞（半截 turn 无异常上报）——已修：streamText/aggregateTurn 均转 PluginException 上报（OpenAI 面同构缺口记档，非本工单回归）
- `openAiCompatWithRetry` 换线后零调用（Speculative Generality）——已修：删除
- `parseProvider` 空白串抛异常与 imageDelivery 空白归缺省口径不一——已修：空白视为未配置归缺省（行级轴 L6）
- 骨架重复约 85 行（Duplicated Code）——记档：工单 10 改映射时一并提取公共件
- 装配选型分支无独立测试（轻缺口）——记档：单行三元，冒烟由装配级覆盖

### 测试覆盖
- 新增：AnthropicMessagesAdapterTest 7 用例（鉴权头/system 单列/tool_use 块+连续 TOOL 合并/tools→input_schema/SSE 三类帧聚合+usage 双帧/错误呈现/message_stop 缺失收口/error 帧）、MockAnthropicServer 夹具、LlmConfigTest 3 用例（缺省/四值/非法+空白）
- 缺口：真端点验收件待用户手动确认

## Standards 轴原样分列

- 硬违规：红线 6（CHANGELOG，已修）+ 红线 3 部分（模块划分.md，已修）
- 核对通过：依赖方向（llm 仅依赖 core）、红线 4 零新依赖、插件配置参考新节与实现逐条一致、provider String+常量沿 imageDelivery 先例、无 Speculative Generality
- 基线：Duplicated Code 骨架 85 行（记档至工单 10）、Long Parameter List（兼容构造缓解）

## Spec 轴原样分列

- (a)：CHANGELOG 缺（已修）、装配选型测试轻缺口（记档）；effort 映射/max_tokens 可配置/真端点验收/deepseek-glm 映射按约定不计
- (b) Scope creep：无
- (c) 实现正确性：provider 解析三面、请求映射六项（system/交替/TOOL 合并/tool_use input/tools input_schema/max_tokens）、SSE 聚合帧序、图片形态、选型接线、文档同 diff——全部对照通过

## 行级规则轴原样分列

- 存留 6 项：medium 1（SSE error 帧，已修）、low 5（孤儿 delta 边界、魔法数字 10s、errorFrom 绕行、openAiCompatWithRetry 死代码已删、空白 provider 口径已修）——处置：修 3 / 记档 3
- 过滤误报 8 条（errorFrom 注释降级、中断位恢复、TOOL 合并边界、防御写法等）

## 审查报告（第 2 轮）：修复复核

第 1 轮修复均为小改（error 帧抛异常、空白归缺省、删死方法、文档两行），由新增/既有测试锁定（848 用例 BUILD SUCCESS）——按 SKILL 第 4 步未触发三轴复跑。
