# 05: duo-workflow + 根 AGENTS.md 路由同步改写

## What to build
改写 duo-workflow（入口 router）：L1/L2/L3 箭头链流程补各节点 Done when；"模型不可自动触发"等散文约束按探测结果落到最强可用层；跨技能引用改显式调用；description 三规则重写。根 AGENTS.md 路由表与 description 同 diff 双向同步（触发词与措辞一致）。改写排改写组第一张——后续每票的触发命中重放都在新路由下运行。

验收标准（重放 seam）：用真实口语说法（"开发个功能"/"提交一下"/"验收"等 ≥6 种）触发路由，核对命中预期技能与 L 级判定。

## Blocked by
04

## Status
ready-for-agent

## Checklist
- [ ] duo-workflow SKILL.md 按规范改写（Done when ×3 级、显式调用、description 重写）
- [ ] AGENTS.md 路由表同 diff 同步
- [ ] 重放：≥6 种口语说法路由命中核对，记录对照表
