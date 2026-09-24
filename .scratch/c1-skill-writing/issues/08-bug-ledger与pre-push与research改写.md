# 08: duo-bug-ledger + duo-pre-push-checks + duo-research 改写

## What to build
按规范改写三技能：duo-bug-ledger 反模式节改正向完成判据（每步建档/排查/归档的 Done when，否定收敛到至多一条硬护栏），与 diagnosing-bugs 的分工引用改显式调用；duo-pre-push-checks 失败路径补 fail-fast（环境疑似问题的举证步骤），description 重写；duo-research 的 `../../../docs` 三级外链收敛为显式指引，"拉最新"流程补插件缓存类参考项目的适配说明（对照 mattpocock 案例的锚点语义）。

验收标准（重放 seam）：报一个真实小缺陷按新流程建档归档；一次真实推送按新证据表执行；一次真实研究任务走新引用与锚点流程。

## Blocked by
04

## Status
ready-for-agent

## Checklist
- [ ] 三技能 SKILL.md 按规范改写
- [ ] 重放：真实缺陷建档→归档全流程
- [ ] 重放：真实推送证据表执行
- [ ] 重放：一次研究任务（引用/锚点流程）
