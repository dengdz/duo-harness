# 02: 移出项与 1.0 后菜单终审 + backlog 陈账销账

## What to build

1.0 边界明示：ADR-0024 移出项清单 + backlog「1.0 后菜单」逐条明示出栈（项名 / 当初移出理由 / 1.0 后从菜单挑活的入口），出栈清单落 acceptance.md（判定第一项「候选全清」证据附件之二）；backlog 陈账同 diff 销账——「03-高级章节」条目（实际已随 0.25.0 交付，条目仍挂「未实施」勘误注）等逐条核对 Status 与实际交付物后处置。spec 见 [spec.md](../spec.md)。

## Blocked by

无（可即开）。

## Status

ready-for-agent

## Checklist

- [ ] 移出项终审：ADR-0024 移出项清单每条 = 项名 / 移出理由（原文）/ 明示出栈声明
- [ ] 1.0 后菜单终审：backlog 对应节约 15 条逐条 = 项名 / 来源 / 明示保留菜单（不因 1.0 清零）
- [ ] 出栈清单落 acceptance.md
- [ ] backlog 陈账销账：「03-高级章节」改已交付销账注（0.25.0 CHANGELOG 在案）；其余条目逐条核对 Status 与交付物，拿不准标「待核」不硬销（对账销账纪律：工单 Status + 交付物实体 + 时间线三核对）
- [ ] backlog 改动交用户目测验收；本单无用户可见变更（backlog 属工程设施），不记 CHANGELOG
