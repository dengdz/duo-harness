# 04: microcompact 裁剪核心

## What to build
对话历史逼近窗口上限时的免模型本地裁剪：程序直接裁掉早期轮次、保留最近 N 组完整对话，不发起总结调用。媒体引用与错误信息豁免保留（排障依据不丢）。每次裁剪落会话事件（可回放可审计——裁剪是破坏性操作，spec 风险记档）。交付后的可感行为：超长会话继续聊不卡、不额外等一次模型调用，早期内容按规则消失而最近对话完整。

设计要点：触发阈值与 N 缺省值工单期定（探测工单 11 压缩治理为依据）；与既有 summary 压缩的触发关系（谁先谁后）在 05 处理，本票先让裁剪机制本身成立。

## Blocked by
01

## Status
done（2026-09-25 用户实测验收通过——滚动裁剪两次触发、原始日志可回放、零 summary 烧钱）

## Checklist
- [x] 裁剪机制落地（触发阈值 + 保最近 N 组，缺省值实现期定并在配置可见）
- [x] 媒体引用与错误信息豁免保留
- [x] 裁剪落会话事件（可回放可审计）
- [x] tdd 红绿循环（裁剪算法 + 豁免用例，seam 见 Comments 记档）
- [x] 术语表「microcompact」词条同 diff

## Comments
- 2026-09-25：**机制裁定（探测工单 11 同构）**：裁剪粒度 = 「最近 5 组完整对话之外的可压缩白名单工具结果」替换占位标记——用户/助手文本不动（早期约束不丢，token 大头在工具输出）；分组锚 = USER 消息（ZCode 按 assistant 轮，语义等价、投影层更稳健，已记 javadoc）。触发 = 治理计量 ≥ min(0.9×压缩阈值, 压缩阈值−2000)（ZCode 公式）；豁免 = 白名单外/失败结果/媒体附件/短于 500 字符；minSaving 256 token 放弃。N=5、开关、阈值公式均在 governance 段/常量可见。
- 2026-09-25：**关键机制**：裁剪 = 落 `context/microcompacted` 事件（被清 callId 名单 + 释放估算）+ 投影层替换——JSONL 原文完整（模型视角不可恢复、审计视角可回放，spec 风险记档满足）；名单跨裁剪点累积、压缩点重置（防压缩后同名调用误伤）。tool/result 事件新落 `error` 失败标志（豁免依据；旧日志缺省 false 语义兼容，序列化仅 true 落盘）。micro 生效当轮 compaction 计量改用裁剪后估算——避免刚省钱又立刻烧 summary（Spec 轴判定属 04 合理范围，05 复核计量语义）。
- 2026-09-25：**TDD seam**（自主模式按 spec Testing Decisions 定）：①选择器纯函数矩阵 8 用例（白名单/失败豁免/媒体豁免/分组保留/组数不足/过短/最小节省/顺序）②投影 4 用例（占位替换/名单累积/压缩点重置/重放恢复）③治理挂点 3 用例（触发+事件痕+替换/开关/放弃后压缩照常）。15 用例先行，实现期两遍法修正一处流式遍历缺陷（测试抓到）。
- 2026-09-25：**留 05 的接口现状**：micro 先于 compaction 的顺序已定（ZCode 同序）；熔断计数、rapid-refill、summary 请求预算隔离未动（05 范围）；microApplied 时 compaction 计量口径切换（usage→本地估算）请 05 复核。
- 2026-09-25：全量 969 用例 0 失败（2 既有 skip）。
- **验收通过（2026-09-25，用户实测 + 日志证据核对，会话 20260925-180850-9f21）**：小窗口配置（窗口 3000 / keepRecent 2）触发滚动裁剪——第 3 轮请求清第 1 组 read 结果（freedTokens=699）、第 4 轮请求清第 2 组（freedTokens=475），两次 `context/microcompacted` 事件落盘；模型行为判据过：明确说"第一轮读 pom.xml 的原始工具结果已被 microcompact 清除、无法凭记忆逐字引用"，但仍记得自己第一轮的总结（assistant 文本保留语义精确命中）；原始 JSONL 原文完整（可回放）；`context/compacted` 零次——零 summary 烧钱。验收用临时 yml 配置已还原。

## 审查轮（2026-09-25·第 1 轮·四轴）

**覆盖**：14 文件 = 已审 14 + 跳过 0（行级轴主代码 6 文件 100%）

### 阻断
- **`docs/05-参考/插件配置参考.md:154`** [Standards]：插入行吞掉 `### subagent 段` 标题且表格体裁与段内 yaml 块不齐 → **已修**：恢复标题，新字段改 yaml 注释体裁入块
- **`docs/05-参考/术语表.md`** [Standards]：microcompact 词条重复两条且落错域（挂在 memory 域，姊妹词条「压缩点」在会话体验域）→ **已修**：去重，移至压缩点词条后并补姊妹指针
- **`SessionEvent.java:152`** [Standards/行级]：MICROCOMPACT javadoc 称"数组 JSON"实为对象载荷（同契约双表述）→ **已修**：改述对象形态；类 javadoc 补 `@param error`
- **`Microcompact.java:60`** [行级]：`Set.of.contains(null)` NPE（手改日志 toolName 缺失场景）→ **已修**：null 前置判
- **`MemoryBook` 同类问题回看**：无（03 已修）

### 建议
- **`/4` 魔数** [Java C-01/行级] → **已修**：两处改 `ContextBudget.CHARS_PER_TOKEN`
- **static 常量可见性** [Java O-18] → **已修**：Microcompact 三常量收 private、KEEP_RECENT 降包私有
- **循环内 new ObjectMapper** [Java CTRL-08/行级] → **已修**：microcompactClearedIds 复用 Session 共享 JSON（SessionEvent 工厂内一处为低频 append 路径，记档沿 SkillTool 先例）
- **测试 Object 可变参数** [Java O-03] → **已修**：重构为显式 Map.of + meta 工厂
- **阈值可为负** [行级] → **已修**：Math.max(1, …) 钳制
- **govern javadoc `@param session` 失实 + 类 javadoc 管线缺 microcompact** [Standards] → **已修**
- **事件类型表字段表缺 error 行** [Standards] → **已修**（usage 既有缺口一并补；tool/result 行补 error 语义）
- **缺"micro 节省不足仍正确触发 compaction"用例** [Spec] → **已修**：短结果用例补断言（无裁剪痕 + summary 照常发起 + 压缩点落盘）
- **microApplied 引用同一性判定** [行级] → **已修**：javadoc 点明契约
- 记档不修：SessionEvent 工厂 try-catch（SkillTool 同款先例）/ 坏 JSON 静默降级零日志（session 模块零日志设施 + 投影热路径防刷屏，javadoc 已述）/ 白名单硬编码（与工具目录对账指引记档，改名漂移靠 ToolCatalog 式对账可兜）/ 组锚 USER 与 assistant 轮偏差（等价语义，javadoc 已记）

### 测试覆盖
- 三 seam 15 用例：选择器矩阵 8、投影 4（含重放恢复）、治理挂点 3（含放弃后压缩照常、压缩计量隔离）
- 既有 compaction 测试全部兼容（Tuning 构造补位）；实现期两遍法修正由投影用例抓回（先写测试后调实现的实际红绿）
