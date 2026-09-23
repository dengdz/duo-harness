# 10: /effort 思考等级四行映射

## What to build

`/effort` 四档归一 off/low/medium/high（缺省 medium）；无参显示当前档；切换落会话事件、下一 turn 生效；请求侧参数映射按 provider 声明走四行——anthropic→thinking + budget、openai-compat→reasoning_effort、glm→thinking 开关、deepseek→显式降级标注（提示「思考请切 reasoner 模型」）；不支持即显式降级标注（哪档映射到什么/为什么不生效），永不静默；辅助性请求（标题生成等）强制 low 档，不因用户调 high 而烧大钱。

决策依据：ADR-0026 决策六 + 决策七（Anthropic 行落地面）；docs/research/ 两家 LLM与上下文/LLM调用层.md。

## Blocked by

08（映射表驱动与 Anthropic thinking+budget 落地面）

## Status
done

## Checklist
- [x] `/effort` 命令 + 会话事件 + 缺省 medium（无参显示当前）
- [x] 四行映射请求体（适配器测试断言参数：thinking+budget / reasoning_effort / thinking 开关）
- [x] DeepSeek 显式降级标注；GLM 开关二值化映射
- [x] 辅助性请求强制降档
- [x] 测试（先例 OpenAiCompatAdapterTest + 工单 08 Anthropic 适配器测试 + ChatAgent seam）
- [x] 工单级验收件：各 provider 档位切换实测演示，用户手动确认
- [x] CHANGELOG 记账（0.19.0 段）

## Comments（续）

- 2026-09-23：实现完成 + 四轴审查（Standards / Spec / 行级规则 / Java 规范，基点 fcee56b 工作树）+ 修复收口。测试 +12 用例（LlmConfigTest 15 / AnthropicMessagesAdapterTest 11 / OpenAiCompatAdapterTest 21 / SessionTitlesTest 6），全量 BUILD SUCCESS。
- **审查修复 9 项**：①行级 high——Anthropic 思考档下工具循环回传缺 thinking 块（协议 400）：aggregateTurn 采集 thinking/signature/redacted_thinking 块，经 reasoningContent 通道持久化（tool/call 事件既有通道），assistant 回传时 content 数组首插块 JSON；②Standards 阻断——会话事件类型表补 model/effort 行（红线 3）；③Spec 偏差——ContextGovernance 压缩摘要请求降 low（工单「标题生成等」的「等」）；④1024 魔法值提 MAX_TOKENS_HEADROOM 常量；⑤两处超 120 字符行换行；⑥LlmConfig javadoc 补 @param effort；⑦两套件叙述用例数更正；⑧CHANGELOG low 档 max_tokens 措辞精确化；⑨SessionTitles 补 off 档升档权衡注释、load() 补 effort 运行时态声明。
- **记档（沿工单 09 先例）**：①ChatAgent seam 级 /effort 用例缺席（命令夹具重，验收件手动覆盖）；②model/effort 无投影无 resume 横幅（工单字面无提示要求——与 /new 无 intent 记档同理，后续工单如需 effort resume 提示再议）；③effortOf 两适配器 3 行逐字重复（各自独立演化形态，下沉抽象过早）；④FQN 内联沿 /model 先例。
- **Spec 轴确认待议**：缺省 medium 使存量 openai-compat 请求开始携带 reasoning_effort——严格兼容网关若拒识该字段可能报错（ADR-0026 决策六已裁定缺省 medium，验收时留意）。

## 审查报告（第 1 轮·四轴）：工单 10 全 diff（基点 fcee56b 工作树，14 文件）

**覆盖**：14 个文件 = 已审 14 + 跳过 0（覆盖率 100%）；行级轴名单 7 主代码文件（OCR delegate preview 产物），测试/文档由 Standards/Spec 轴覆盖

### 阻断
- **`docs/05-参考/会话事件类型表.md`** — Standards 轴：新增 `model/effort` 事件未登记类型表（红线 3，工单 09 有 model/intent 先例）——已修
- **`AnthropicMessagesAdapter.java:205-221`** — 行级轴 high：思考档下工具循环回传缺 thinking 块（扩展思考协议要求 assistant 回合 thinking 块含 signature 原样回传，缺失即 400）——已修（见下）

### 建议
- **`ContextGovernance.java:357`** — Spec 轴偏差：压缩摘要请求未降档（工单「标题生成等」的「等」）——已修（effortOverride=low）
- **`AnthropicMessagesAdapter.java:220`** — Java 规范轴 MAJOR（C-01 魔法值）：budget+1024 抬升余量提 `MAX_TOKENS_HEADROOM` 常量——已修
- **`CliPluginTest.java:435` / `OpenAiCompatAdapterTest.java:31`** — Java 规范轴 MAJOR（F-09 超 120 字符）——已换行
- **`LlmConfig.java` javadoc** — Standards 轴：12 组件缺 `@param effort`——已补
- **套件叙述用例数** — Standards 轴：LlmConfigTest/AnthropicMessagesAdapterTest 标值与实际不符——已更正（15/13）
- **CHANGELOG 措辞** — Spec 轴：「抬至 budget+1024」对 low 档不精确——已改「保持不小于」（low 档维持 8192）
- **`SessionTitles.java`** — 行级轴 low：off 档恒 low 的升档权衡——已补注释
- **`LlmConfig.load()`** — 行级轴 medium：effort 不解析 yml 需显式声明——已补注释（运行时态，防「永不静默」缺口）

### 记档（不修，附理由）
- effortOf 两适配器 3 行逐字重复（Standards）：下沉抽象过早，两适配器独立演化形态
- model/effort 无投影无 resume 横幅（Standards/行级）：工单字面无提示要求，与 /new 无 intent 记档同理
- FQN 内联（Standards/Java 轴）：沿 /model 命令与仓库既有风格
- ChatAgent seam 级 /effort 用例缺席（Spec）：命令夹具重，沿工单 09 先例由验收件手动覆盖

### 四轴原样分列

**Standards 轴**：阻断 1（事件类型表，红线 3）；建议 5（javadoc/套件叙述/effortOf 重复/FQN/effort resume 不对称记档）；过滤误报 9（/model 同构照抄系本仓库口径、effortNote 内聚合理、applyEffort 修饰不对称非问题、max_tokens max() 语义不失真等）。测试覆盖：核心逻辑全覆盖，缺口 = /effort 命令交互面（沿先例验收件覆盖）、openai-compat override=off 压 config=high 分支。

**Spec 轴**：(a) 缺失——ChatAgent seam 测试面（记档沿先例）+ 压缩摘要降档偏差（已修）；(b) scope creep 无；(c) 正确性逐字对照全过（budget 档值/max_tokens 协同/glm 方向/deepseek 双缺/override 优先级/off 形态）。风险提示：缺省 medium 使存量 openai-compat 请求开始携带 reasoning_effort，严格兼容网关可能拒识（ADR 已裁定，验收留意）。

**行级规则轴**：候选 10 → 修 3（high thinking 回传、medium load 静默、low off 升档注释）/ 剔 7（误报理由逐条记档：单线程命令、防御性构造、仓库既有风格、有意的显式降级空分支等）。覆盖率 7/7。测试缺口与 high 相关——已补 2 用例。

**Java 规范轴**：MAJOR 2（C-01 魔法值、F-09 超长行）均已修；无 BLOCKER/CRITICAL。过滤记档：C-05 enum（仓库 String 常量惯例优先）、UT-12 命名（中文测试名先例）、CON/E/L 系列无新增场景、S-04 已满足（effortAllowed 白名单）、O-10 压缩构造归一为既有形态。测试覆盖良好（mock server 无外部依赖），轻缺口两条均不阻塞。

### 修复复核

修复含阻断级新代码（thinking 块采集回传 +2 回归用例锁定）——按 SKILL 第 4 步，新增回归测试锁定后免复跑四轴。
- 2026-09-23：**验收通过（用户手动）**——真实配置（provider=anthropic，api.deepseek.com/anthropic 端点）实测：①缺省 medium 首条消息正常回复（thinking+budget 8192 被 DeepSeek Anthropic 兼容端点接受，Spec 轴兼容性风险解除）；②/effort 无参显示当前档+四档清单+映射说明；③off/high 两向切换并实测下一轮正常回复（high 档 16384 budget 兼容）；④非法档 ultra 拒切；⑤resume 横幅不受影响。glm/openai-compat 行请求体行为由 21 个适配器用例锁定（无对应真实端点，跳过手动）。
