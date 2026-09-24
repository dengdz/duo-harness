# 01: Matt 侧机制精读与机制清单

## What to build
全量一手重读 mattpocock-skills（37 个 SKILL.md + CONTEXT.md + invocation.md + writing-docs.md + writing-for-agents/SKILL-MECHANICS.md，约 2900 行），产出机制级精读清单，落 `docs/research/mattpocock-skills/` 底稿。每条机制带原文锚点（文件:行号）与一句话机理：调用权分流（user-invoked 硬隔离）、依赖=显式 Skill-tool 调用、完成判据（Done when / 出示证据）、用户检查点（阻塞步骤 + Wait）、领先词、否定句治理、信息层级与剪枝（sediment/no-ops）、词汇治理（CONTEXT.md Avoid 清单）、失败路径 fail-fast。既有通读笔记（2026-09-24 会话）可作起点，但结论须在执行会话中独立推导核实。

验收标准：清单覆盖全部机制主题且每条可回溯到原文；新会话读者只看清单即可理解"Matt 技能为何严格"。

## Blocked by
无（可立即开工）

## Status
ready-for-agent

## Checklist
- [ ] 37 SKILL.md + 4 元文档全量重读（锚点 = 插件版本 1.2.3 核对无变化）
- [ ] 机制清单落 docs/research/mattpocock-skills/ 底稿（每条带 文件:行号）
- [ ] 与背景线索（Downloads 两份轻量分析）逐条比对，修正处标注
