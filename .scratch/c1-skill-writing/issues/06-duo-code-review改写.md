# 06: duo-code-review 改写（证据先行收口 + fail-fast 预检 + 历史迁出）

## What to build
按规范改写 duo-code-review：收口判据改证据先行（出示四轴报告落盘路径，缺一即停）；第 0 步 fail-fast 基点预检（rev-parse 成功 + diff 非空才派发）；跨技能引用（code-review/duo-java-review/OCR delegate）改显式调用；豁免条款三重否定收敛为两条件正向判定（保留双处记档 + 显式确认的治理语义）；description 重写；变更历史 6 段迁出 SKILL.md（入 references/experience.md）。保留：brief 填空模板、四轴结构、豁免治理。

验收标准（重放 seam）：取一个真实未审小 diff 按新流程完整跑四轴，出示"新旧流程行为对照"（收口出示的路径清单、预检行为、报告形态）。

## Blocked by
04

## Status
ready-for-agent

## Checklist
- [ ] SKILL.md 按规范改写（收口/预检/引用/否定收敛/description/历史迁出六面）
- [ ] experience.md 接收变更历史（内容无损迁移）
- [ ] 重放：真实小 diff 四轴全流程，新旧行为对照清单交用户
