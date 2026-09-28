# 19: FQCN、魔法数字与注释失真清理批

## What to build
机械清理批三件事：① 全限定类名约 60+ 处改为正常 import（保留有意的 api→internal 委托风格白名单，白名单记档）；② 魔法数字命名化——输出收集宽限、索引深度上限、应答超时、轮询间隔、插队截断长度、缩进宽度等提为命名常量；③ 注释/javadoc 失真修正（类 javadoc 鉴权描述过时、字段注释描述另一字段、"七类"实列八等）+ 配置错误处理统一为点名口径（两处静默回退对齐多数派）。

证据锚点：审计报告 agent 组第 27 条 + 各模块 P3 魔法数字/注释失真/配置口径条目。

验收标准（用户可感）：同文件 import 与全限定并存的自相矛盾消除（白名单外）；所有裸数字有名字；配置配错值时得到点名报错而非静默回退。

## Blocked by
04, 08, 18

## Status
in-progress（实现完成待手动验收，2026-09-28；FQCN 项缓办记档）

## Checklist
- [x] 魔法数字命名化（HookRunner 输出收集宽限 2s、FileReferenceIndex 深度上限 15、WebPlugin 应答兜底超时 10min——三处提常量；CliPlugin 的 40/60s/200ms/11 空格散点与 llm 超时散点记档随后续 lint 轮）
- [x] 注释/javadoc 失真逐项修正（WebPlugin 鉴权描述、AttachmentStore 字段注释错位、NdjsonFrames 计数、WebAnswerer.currentPending 消费方描述）
- [x] 配置错误处理统一点名口径（agents-md/memory 两处 budgetChars 非法值从静默回退改 FAILED 点名，文档同步）
- [x] 全仓 verify 全绿
- [ ] FQCN 批量清理——**缓办**：脚本批一次实跑因 import 解析缺陷破坏 890 处（import 行自毁）已整体回滚重来；保守批（仅「已 import 却写全限定」的自相矛盾形态）实测 0 处。全量 FQCN（约 60+ 处，多属未 import 的全限定）建议独立 lint 轮以 IDE organize-imports 级工具处理，不在本批强推

## Comments

- 2026-09-28 实现：审计的「同文件 import 与 FQCN 并存自相矛盾」形态在现库实测已不存在（保守批 0 处）——现存 FQCN 均为「未 import 的全限定」形态，安全简化需加 import，脚本批已证风险（一次失败回滚，工单 Comments 记档全过程）。行为变更仅配置口径统一一项（CHANGELOG 记账）。全仓 verify BUILD SUCCESS。

