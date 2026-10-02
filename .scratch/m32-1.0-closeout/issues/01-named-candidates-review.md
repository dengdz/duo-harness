# 01: 具名候选终审档案

## What to build

ADR-0007 具名候选清单逐项核对「已做（对应里程碑）/ 明确不做（出栈决议锚点）」，终审表落 acceptance.md（`.scratch/m32-1.0-closeout/` 下新建，本单先落「终审节」，作为 1.0 判定第一项「候选全清」的证据附件）。逐项凭证锚点（ADR / CHANGELOG / 里程碑目录）grep 实证可达。spec 见 [spec.md](../spec.md)（范围锚定 [ADR-0034](../../../docs/adr/0034-M32收口盖章立项决策.md)）。事实基础已预核（ADR-0029：「现状已全 done 或出栈，走手续」）——本单只做逐项核对与出档案，不重开任何决议。

## Blocked by

无（可即开）。

## Status

ready-for-agent

## Checklist

- [ ] acceptance.md 建档（判定四项证据核对表的载体；本单落「具名候选终审」节）
- [ ] ADR-0007 候选清单逐项核对：每项 = 候选名 / 归宿（对应里程碑号 + 交付凭证）/ 或出栈决议锚点，凭证逐项 grep 实证
- [ ] 终审表交用户目测验收（done 定义按 issue-tracker）
- [ ] 本单无用户可见变更，不记 CHANGELOG
