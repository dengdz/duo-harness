# 09: PluginSnapshot javadoc 认事实

## What to build

插件档案卡（PluginSnapshot）的 javadoc 改为与实际行为一致：快照 name 一律为插件类全名（Boot 装载与编程挂载同此，yml id 不进 name）。行为修正（name 用 yml id）归装载域 1.0 后（H-08/H-16 同域），本期不越界（ADR-0030 裁定、Q4 grill 裁定）。

## Blocked by

无（顺带小单）。

## Status
in-progress（待手动验收，攒统一拍板）

## Checklist
- [x] javadoc 修订，与实现事实一致（含 BootYmlTest 按实现断言的口径说明）
- [x] 零行为变化（core 模块测试全绿；全仓 verify 随相邻工单收口联合确认）

## Comments

- 2026-09-28：javadoc 改「一律为插件类全限定名（yml 装载与编程挂载同此；yml 行 id 仅作装载行标识，不进快照名）」。待手动验收（攒统一拍板）。
