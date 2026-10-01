# 07: 指南新增——导出与检索 + 子代理

## What to build

两篇新指南：①导出与检索（/export 交付清单与变更摘要章节、显式 sessionId 导出、/search 会话检索语法与授权边界）；②子代理（任务分解与派发、知识可见性 opt-in、并发调度与任务管理）。

## Blocked by

01（大纲确认）。

## Status

ready-for-agent

## Checklist

- [ ] 导出检索篇成稿：导出触发方式（命令/端点）、清单内容构成、检索用法与 cwd 授权边界
- [ ] 子代理篇成稿：派发时机、模板与可见性 opt-in、并发任务查看（task_output/task_stop）
- [ ] 命令行为逐条实测（活体引用）
- [ ] 内链自查 + vitepress build 通过
- [ ] 用户验收通过；CHANGELOG 0.27.0 段同 diff 记账
