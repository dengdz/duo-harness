# M8.5 Spec：文档站补全——框架描述 / 设计思想 / 使用方式

> 插入阶段（用户增设，2026-09-14）：M8 收官后站点审计暴露内容极薄——五章骨架三章"建设中"，三层核心诉求（框架描述 / 设计思想 / 使用方式）只有 ADR 与 demo 教程撑着。参照 DSH 建站方式（`website/` VitePress、docs/ 含 architecture / capability-seams / tool-catalog / subsystems 30+ 篇、英中双语——见 [docs/research/DSH/总览.md](../../docs/research/DSH/总览.md)），按"中文单语、务实够用"路线补齐。**落 0.3.0 分支**（分支纪律首次执行：0.3.0 已建，未建分支不进入实现）。

## Problem Statement

一个想采用 duo-harness 的人（包括三个月后的自己）打开文档站，看到的是：一篇跑 demo 的入门、一篇 84 行的模块划分、八篇 ADR 和一份限制清单。想知道"这个框架能为我做什么""为什么这样设计""不用 demo 的话怎么组装自己的 agent"——站上没有答案；术语表（CONTEXT.md）甚至不在站上，index 的链接点进去是死的。文档与代码的差距正在变成采用成本。

## Solution

在不改站点基础设施的前提下（VitePress + Pages 已工作），补五篇内容 + 一个防漂移机制，对齐五章骨架：04-架构《设计主线》（框架描述 + 设计思想导览）、02-指南《组装你的第一个 agent》（使用方式核心教程）、05-参考《插件配置参考》《会话事件类型表》《术语表》（CONTEXT.md 迁入）；工具目录手写 + 对账测试锁定（生成器的最小形态：5 个工具量级，测试断言文档与注册工具一致防漂移——DSH"生成并校验"思路的务实版）。中文单语，教程必须可照抄运行。

## User Stories

1. As a 潜在采用者, I want 打开站点十分钟内明白这个框架是什么、能为我做什么, so that 我能判断值不值得往下看
2. As a 潜在采用者, I want 一篇设计主线导览把 8 篇 ADR 串成因果故事, so that 我不用自己排阅读顺序
3. As a 使用者, I want 照着《组装你的第一个 agent》一步步贴代码和 yml 就能组装出自己的 agent, so that 我不依赖读 demo 源码
4. As a 使用者, I want 一份插件配置参考逐行解释每个 yml 插件行与其 config, so that 我增删插件不用翻源码
5. As a 使用者, I want 一份会话事件类型表说明每种事件的字段与投影规则, so that 我理解会话文件里每一行是什么
6. As a 使用者, I want 术语表在站上可查, so that 遇到 seam/six-state/快照等词不用离开站点
7. As a 框架维护者, I want 工具目录有对账测试, so that 工具增删后文档漂移会在测试里炸出来
8. As a 框架维护者, I want ADR 导览表随 ADR 增减同步维护, so that 设计思想的入口不腐坏
9. As a agent 技能, I want 术语表的规范路径稳定且在文档站可见, so that duo-tracker 等技能引用不因迁移失效
10. As a 潜在采用者, I want 站内链接全部可达, so that 浏览体验不中断

## Implementation Decisions

- **受众与语言**：中文单语；口吻面向"想跑起来 / 想嵌入"的读者；不双语、不搬独立 website/ 目录（docs/ 内嵌 VitePress + Pages 链路已通）
- **《设计主线》**（04-架构）：三支柱（插件容器 / 工具域 / 交互 seam）→ 呈现位分离（CLI 与 Web 同机制，ADR-0008）→ 会话事件溯源；每段"一句话语义 + 链对应 ADR"，对齐 DSH architecture.md 的三主线写法；文末 ADR 导览表（8 行：编号 / 一句话 / 何时读）
- **《组装你的第一个 agent》**（02-指南）：教程体裁、可照抄——最小 yml（tools + prompts）→ 装配 boot → 加 MCP 行（编程挂载与 yml 等价形态）→ 加交互审批 → 会话续接；每步有可观察结果
- **《插件配置参考》**（05-参考）：agent-demo.yml 全部插件行逐行注解（id / FQCN / config 字段 / 谁注入谁）；声明 config 类型必须带 config 块的约定入册
- **《会话事件类型表》**（05-参考）：8 种事件类型的字段表（type/at/text/toolCallId/toolName/reasoning）+ 投影规则（哪些入对话投影、哪些纯审计/过程）
- **《术语表》**（05-参考）：CONTEXT.md 迁入（内容不删减），仓库根替换为指针文件；同 diff 修复全部入链（docs/index.md、README、duo-tracker 等技能引用，以 grep 为准）
- **工具目录**（05-参考）：手写（当前 5 工具量级）+ **对账测试**（example 模块：demo 装配后遍历 ToolsService，断言每个工具的 name 与 description 均出现在 docs/05-参考/工具目录.md 中）——生成器在工具量级上来后升级
- **导航与排除**：config.mts 的 05 参考 / 02 指南分组补条目；srcExclude 维持（agents/research 不上站）
- **分支**：全部落 0.3.0（分支纪律首次执行）；M8.5 收官后 CHANGELOG 0.3.0 段记账

## Testing Decisions

- 好测试标准：只测外部可见行为——对账测试断言"文档包含全部注册工具的名称与描述"，不测文档措辞
- 防漂移测试放 example 模块（demo 装配是最全的工具装配点）；先例：AgentReplMainTest 的 boot 冒烟
- 全站验证：`npm run docs:build` 零死链阻断；教程"可照抄"以实际跑通一遍为验收（工单内完成）
- 用户验收：站点本地浏览确认三层内容齐备且准确

## Out of Scope

- 03-高级（技能编写 / MCP 深入）、《组装第二个 agent》（多插件协同）——M9+ 候选
- 双语、独立 website/ 目录、tool-catalog 全自动生成器（当前为手写 + 对账测试）
- 架构内容深化（capability-seams 式接缝文档、subsystems 分篇）——规模未到

## Further Notes

- 站点现状基线：index.md 21 行、模块划分 84 行、运行Demo 97 行（M8 段刚对齐实际）；本 spec 不动这两篇的既有内容，只新增
- 文档网站机制（srcExclude / ADR 挂导航纪律 / 构建验证）见 duo-doc-standards 技能；0.2.0 曾两次漏挂 ADR 教训在案
