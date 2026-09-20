# 10: 文档与收口

## What to build

M21 用户可见面收口：参考文档、运行 Demo、CHANGELOG/limitations 记账全部就位，对账测试绿，里程碑验收件备好。全部行为工单（01-09）完成后执行。

## Blocked by

02, 03, 04, 05, 06, 07, 08, 09

## Status

ready-for-agent

## Checklist

- [ ] `工具目录.md` 增 read_image / session_search 条目（工具目录对账测试绿）
- [ ] `插件配置参考.md` 增附件段 / 会话检索段 / `llm.vision` 与 `llm.imageDelivery` 字段
- [ ] `运行Demo.md` 增输入面与会话工具演示段 + demo yml 示例行（web-tools 外新增 attachment / session-query 行）
- [ ] CHANGELOG 0.16.0 段 Added 记账（web 工具族之外的本四件用户可见变更）
- [ ] limitations 新增三条：内存倒排索引量级边界（后期转 SQLite，接口已后端无关）/ 治理 token 估算不含图片（真实 usage 侧覆盖）/ Files API 仅 DeepSeek 形态端点（inline 通用兜底）
- [ ] 验收件准备（duo-acceptance）：工单级最小演示 + 里程碑汇总 Demo，含可复现步骤与日志
