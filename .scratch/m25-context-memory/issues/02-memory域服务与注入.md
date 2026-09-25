# 02: memory 域服务与注入（记忆读路径）

## What to build
记忆本的最小读路径端到端：项目级 `.duo/MEMORY.md`（不入 git）作为记忆本，每次模型请求把其内容注入上下文（meta_user 通道：system 规范段 + 记忆索引），带预算上限；文件不存在时静默降级零报错。交付后的可感行为：用户手写一条记忆进 MEMORY.md，开新会话，AI 无需任何操作即"知道"这条内容。

设计要点（工单期按探测工单 10 细化）：MEMORY.md 服务走既有服务域架构；注入预算上限（超限截断策略实现期定）；`.gitignore` 增加 `.duo/MEMORY.md`（个人记忆不入库——spec 决策四）。

## Blocked by
01

## Status
ready-for-agent

## Checklist
- [ ] MEMORY.md 读写服务（服务域接入，架构一致）
- [ ] 每请求注入（meta_user + 预算上限 + 超限策略）
- [ ] 文件不存在 / 为空时静默降级（未启用用户零感知）
- [ ] `.gitignore` 增 `.duo/MEMORY.md`
- [ ] 端到端演示：手写一条记忆 → 新会话模型可见
- [ ] tdd 红绿循环（seam 先与用户确认）；术语表「memory 记忆本」词条同 diff
