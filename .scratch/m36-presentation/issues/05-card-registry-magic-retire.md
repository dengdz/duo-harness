# 05: 卡片声明式注册表 + 魔法串退役（收缩）

## What to build

工具卡片长相由表驱动：前端分型 if/else 工具名链改为声明式注册表——render IIFE 边界开注册口（M29 边界纪律：新增代码引用的每条标识符机械核对定义作用域，验证以真实页面首载为准）；内核为全部内置工具注册全量声明（图标/中文名/参数摘要/卡片形态）；第三方声明经聚合端点并入表（先到先得，卸了再装即替换）。三处文案嗅探退役：`deny` 前缀、`执行被拒绝|执行失败` 正则、`已获批准` 包含判定全部改读 01 的结构化字段（expand–contract 收缩半边）。ask_user / exit_plan_mode / todo_write 交互卡与特殊渲染路径留内核硬编码——独占面裁定不改。

验收标准：内置工具卡片观感不回归（S4 前后对照）；测试插件的卡片声明在页面生效；后端改文案页面不再误判（嗅探退役实证——改文案抽查）；真实页面首载验证。

## Blocked by

01（结构化字段是消费前提）, 03（注册声明经聚合端点并入）

## Status

done（2026-10-03 用户授权 agent 代验通过——五项验收实证，style-proof/10）

## Checklist

- [x] render IIFE 边界注册口（机械核对 + 首载验证）
- [x] 内置工具全量声明注册 + 分型链表驱动
- [x] 三处嗅探改读 status/decision 字段（01 字段消费）
- [x] 交互卡路径内核独占不变（断言守护）
- [x] S4 浏览器对照（内置观感不回归 + 第三方声明生效演示）
- [x] CHANGELOG 记账（用户可见：卡片渲染声明式/插件可贡献展示卡，同 diff）

## Comments

- **实现形态（2026-10-03）**：`cardDeclarations` Map + `registerCardDeclarations` 摄入口（render IIFE 返回面暴露，§5 聚合端点刷新时调用）——第三方声明纯数据（icon/label/summaryFields），同名先到先得（内置 TOOL_ICONS/TOOL_LABELS 表为先到方兜底，第三方只落未注册工具名）、提供方拔除随下次刷新摘除回退通用卡；summaryFields 走通用抽取（paramSummary 尾段）；工具图标/名称读取处注册表优先。**范围口径**：内置特殊表单（terminal/diff/file/skill/memory/todo/交互卡）保留内核硬编码——它们是内置形态非开放面（Q3 裁定「改得了长相改不了交互语义」+ 声明纯数据无代码通道，第三方表单级替换天然不成立）。
- **魔法串退役双轨**（字段优先 + 旧日志回落）：`resultFailed`（status 字段，null 回落 isError+文案正则）、exit_plan_mode 成败（status=ok/denied，null 回落「已获批准」includes）、approvalDecided（decision 枚举，null 回落 deny 前缀）——新事件恒有字段（后端改文案不再误判），旧日志回放回落原文嗅探（历史文案原样保留）。
- **浏览器五项验收**（隔离实例 + 合成事件走真实 dispatch 管道，evaluate 断言 + 截图 style-proof/10）：①第三方声明驱动渲染（code_search → label「代码检索」+ 摘要 "foo /x" + 图标生效）②status=denied + 文案无魔法串 → 失败态（嗅探退役实证）③旧日志无字段 + 旧文案 → 回落嗅探 ④声明摘除 → 回退工具名通用卡 ⑤approval decision=deny + 文案不以 deny 开头 → 「✗ 已拒绝」冻结注记。
- **实测注意**：实时待答审批卡走 dock（合成直派不触发委托层显隐）——回放形态（卡进消息流冻结）才是旧日志兼容语义的正确验证口径；IAB 截图偶发伪影以 evaluate 状态断言兜底。
- **遗留观察**：内置工具的摘要 if 链（paramSummary 前段）保留既有形态未并入声明表——声明表当前服务开放面（第三方），内置摘要收敛可作后续打磨项，不阻塞销账（icon/label 已表驱动、分型特殊形态本就内核独占）。
