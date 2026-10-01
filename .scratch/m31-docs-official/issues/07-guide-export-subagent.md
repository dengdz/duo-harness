# 07: 指南新增——导出与检索 + 子代理

## What to build

两篇新指南：①导出与检索（/export 交付清单与变更摘要章节、显式 sessionId 导出、/search 会话检索语法与授权边界）；②子代理（任务分解与派发、知识可见性 opt-in、并发调度与任务管理）。

## Blocked by

01（大纲确认）。

## Status

done（2026-10-01 用户验收通过）

## Checklist

- [x] 导出检索篇成稿：/export 双形态与双呈现位落点（CLI 写盘/Web 下载/一次性触发）、报告两对账章节（交付清单 = 模型申报 × 变更摘要 = git 快照比对，非 git 退化）、检索双入口（侧栏搜索框/session_search）、行为边界表（FTS5 懒构建/cwd 授权边界/索引内容与不入项/命中上限 8/活跃会话实时供数）
- [x] 子代理篇成稿：何时拆（并行独立/大块隔离两类 + 不适合场景）、模板配置样例（tools 白名单/prompt/maxIterations/未知字段 fail-fast/零模板零感知）、五件工具表（spawn/fork/send_message/list_agents/interrupt_agent——锚定源码 javadoc：控制面三件不进子模板可用集、spawn 只收模板名+任务描述）、**知识可见性未实现如实写**（材料须进任务描述原文，锚 limitations）
- [x] 命令行为逐条实测（活体引用——五工具 javadoc 逐个核对、检索边界锚工具目录 session_search 段、对账章节锚术语表「交付声明」「变更摘要」词条）
- [x] 内链自查 + vitepress build 通过（2026-10-01，1.24s）；sidebar 两篇入册；口吻自查零命中
- [x] 用户验收通过（2026-10-01）；CHANGELOG 0.27.0 段同 diff 记账

## Comments

- 2026-10-01 产出：`docs/02-指南/导出与检索.md`（约 60 行）、`docs/02-指南/子代理.md`（约 65 行）。导出篇无前向链接；会话管理与恢复篇此前预留的指路（「详见导出与检索指南」）本单兑现闭环。
