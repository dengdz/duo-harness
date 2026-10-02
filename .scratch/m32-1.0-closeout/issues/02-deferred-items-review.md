# 02: 移出项与 1.0 后菜单终审 + backlog 陈账销账

## What to build

1.0 边界明示：ADR-0024 移出项清单 + backlog「1.0 后菜单」逐条明示出栈（项名 / 当初移出理由 / 1.0 后从菜单挑活的入口），出栈清单落 acceptance.md（判定第一项「候选全清」证据附件之二）；backlog 陈账同 diff 销账——「03-高级章节」条目（实际已随 0.25.0 交付，条目仍挂「未实施」勘误注）等逐条核对 Status 与实际交付物后处置。spec 见 [spec.md](../spec.md)。

## Blocked by

无（可即开）。

## Status

done（2026-10-02 用户确认终审与 backlog 改动）

## Checklist

- [x] 移出项终审：ADR-0024 移出项清单每条 = 项名 / 移出理由（原文）/ 明示出栈声明（13 条全表，acceptance.md 附件 B1）
- [x] 1.0 后菜单终审：backlog 对应节约 15 条逐条 = 项名 / 来源 / 明示保留菜单（实际 16 条逐条核对，acceptance.md 附件 B2）
- [x] 出栈清单落 acceptance.md（附件 B1/B2 + 终审发现：ADR-0024 移出项 8 条未入菜单，同 diff 补录兑现「菜单增补」原意）
- [x] backlog 陈账销账：「03-高级章节」改已交付销账注（0.25.0 CHANGELOG 在案）；其余条目逐条核对 Status 与交付物——实销 4 处（PluginSnapshot javadoc→M28、user.dir 提取→M28 Cwd、03-高级→0.25.0、defer 化→0.26.0），注更正 3 处（鉴权条目仅存 bind 半边、工具名协议化时机→1.0 后、Session 拆分评估钩子落空注、菜单 prompt 缓存注更正），无「待核」悬置
- [x] backlog 改动交用户目测验收（2026-10-02 用户确认通过）；本单无用户可见变更（backlog 属工程设施），不记 CHANGELOG

## Comments

- 2026-10-02 产出：acceptance.md 证据附件 B（B1 移出项 13 条 / B2 菜单 16+8=24 条）+ backlog 九处改动（销账 4 / 注更正 4 / 菜单补录 8 条）。销账逐条三核对（工单 Status / 交付物实体 / 时间线）：03-高级三章在站实检、defer 化 M30-05 工单 done 实检、PluginSnapshot javadoc 现文实检、user.dir 收敛 Cwd 专类 grep 实检。
- 2026-10-02 **用户确认终审与 backlog 改动，工单 02 done，工单 04 全部前置解锁**。
