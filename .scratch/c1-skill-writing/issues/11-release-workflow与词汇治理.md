# 11: duo-release-workflow 改写 + 词汇治理 + AGENTS.md 同义词红线

## What to build
duo-release-workflow 按规范改写（检查清单表格保留、失败路径 fail-fast、引用改显式调用、description 重写）；docs/agents/domain.md 增「技能用词」节——每词首选 + Avoid 两行（工单/ticket/issue 统一、验收 vs 验证分工、收口、领先词、上下文指针）；根 AGENTS.md 增红线"SKILL description 不引入词汇表外同义词"；术语表补齐改写过程新增词条（领先词、上下文指针等，随实现同 diff）。

验收标准（重放 seam）：一次真实发版（或演练到推送前检查）按新流程走通；抽查三个 SKILL.md 用词对照词汇表零表外同义词。

## Blocked by
04

## Status
ready-for-agent

## Checklist
- [ ] duo-release-workflow SKILL.md 按规范改写
- [ ] domain.md「技能用词」节 + AGENTS.md 同义词红线（同 diff）
- [ ] 术语表新增词条补齐
- [ ] 重放：真实发版演练 + 词汇表抽查
