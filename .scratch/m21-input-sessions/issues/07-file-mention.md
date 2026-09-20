# 07: @file 提及模式

## What to build

`@path` 路径提及（DSH 同构，零内容注入）：agent `fileref` 子包提供 grammar + 工作区路径索引 + 指南片段（仅 read 工具在册时注入 prompt 注册表——"@ 是路径；要内容调 read；未 read 不得声称已看过"）；Web 输入框 `@` 补全（候选下拉 + 目录下钻 + 防护）；CLI 文本直打天然生效。

## Blocked by

无（与附件线零耦合，可立即开工）。

## Status

in-progress

- 2026-09-20（实现轮）：实现完成、全模块测试绿。
## Checklist

- [ ] grammar：`activeAtToken`（@ 前行首/空白；引号路径 `@"..."`；控制字符拒绝生成）、mention 格式化（目录尾 `/`、含空格才加引号）
- [ ] 工作区路径索引：`maxEntries` 截断、排除目录清单（.git/node_modules/target 等对齐 DSH）、目录 symlink 不跟随、越界返空、子树不可读贡献 0 候选、tool/result 后索引陈旧后台重建
- [ ] 指南片段注入 prompt 注册表（仅 read 工具在册时；无 read 部署零注入）
- [ ] Web 补全端点 + 输入框下拉（候选/下钻/引号路径）
- [ ] 纯函数测试（grammar/索引防护矩阵）+ 补全端点测试 + 指南注入条件测试
