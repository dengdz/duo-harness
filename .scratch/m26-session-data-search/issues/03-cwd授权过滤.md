# 03: cwd 授权过滤——跨会话检索的目录边界

## What to build

跨会话检索结果逐条校验会话 cwd 与当前工作目录**相等才收**：项目 A 的会话里搜不到项目 B 的内容，防跨项目上下文污染；无 cwd 记录的 M26 前旧会话不进结果（老文件本体仍在、可打开，只是不进检索）。session_search 工具与 Web 搜索框两入口行为一致。

决策依据：[ADR-0028](../../../docs/adr/0028-M26会话数据与检索立项决策.md) 决策三（严格过滤；回填与显式跨目录开关为后续升级路径，本期不做）。DSH 对照：`recordAuthorized` 逐条校验形态。

## Blocked by
01, 02

## Status
in-progress

## Comments

- 2026-09-26 开工（0.21.0 分支，Blocked by 01/02 已完成）。

## Checklist
- [ ] 检索管线按 cwd 逐条过滤（相等才收），消费 01 落盘的会话 cwd 字段
- [ ] 无 cwd 的旧会话 fixture 不出现在检索结果（手写无头旧 JSONL 验证）
- [ ] session_search 工具与 Web 搜索框授权边界一致（装配/集成用例）
- [ ] CHANGELOG 记账：老会话不再出现在跨会话检索结果（用户可感知行为变化）
