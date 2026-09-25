# 07: AGENTS.md 迁 meta_user + 嵌套子目录链（M7#3 销账）

## What to build
AGENTS.md 加载链升级（ADR-0024 M25 第三块后半）：① 注入通道从现行迁移到 meta_user（与 cacheControl 断点的身份段划分同改——AGENTS.md 属稳定身份段）；② 嵌套子目录 AGENTS.md 链生效（子目录约定对相应目录下的操作可见）；③ fs 操作后增量发现（会话中目录/文件变更后链可更新）。同 diff 销 docs/limitations.md M7#3 条目并记 CHANGELOG（doc-standards 惯例）。

M7#3 原文口径：「仅用户全局 + 项目根两文件；嵌套子目录链与 fs 操作后增量发现未做 → M25 搭车」——本票把两件未做项都收掉。

## Blocked by
06

## Status
ready-for-agent

## Checklist
- [ ] AGENTS.md 注入迁 meta_user 通道（与 cacheControl 身份段协同，同改同测）
- [ ] 嵌套子目录 AGENTS.md 链生效（端到端：子目录约定对该目录下操作可见）
- [ ] fs 操作后增量发现（会话内变更后链更新，行为口径工单期按 M7#3 记录定）
- [ ] 同 diff 删 docs/limitations.md M7#3 条目 + CHANGELOG 记销账
- [ ] tdd 红绿循环（链加载 + 增量用例，seam 先与用户确认）
